package com.example.tp1.indexing

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper
import android.system.Os
import android.system.OsConstants
import androidx.core.database.sqlite.transaction
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.extraction.MarkdownChunk
import com.example.tp1.extraction.hex
import com.example.tp1.inference.EMBEDDING_DOCUMENT_PREFIX
import com.example.tp1.inference.EMBEDDING_INPUT_BYTES
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceMetadata
import com.example.tp1.sources.SourceStore
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

internal data class IndexIdentity(
    val displayName: String, val inputSha256: String, val modelSha256: String, val maxInputTokens: Int
)

internal data class IndexSnapshot(
    val documentId: Long, val generation: String, val identity: IndexIdentity, val chunks: List<IndexedChunk>
)

internal data class IndexedChunk(val ordinal: Int, val text: String, val inputTokens: Int, val vector: FloatArray)

/** Call on IO. Lock order is sourceLock -> SQLite; no native work runs here. */
internal class IndexStore(context: Context, private val file: File = databaseFile(context)) : Closeable {
    private val context = context.applicationContext
    private val owned = mutableSetOf<String>()
    private val helper = object : SQLiteOpenHelper(this.context, file.absolutePath, null, 2,
        { throw SQLiteException("Index database is corrupt") }) {
        override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE documents (
                id INTEGER PRIMARY KEY, source_uri TEXT NOT NULL, document_uri TEXT NOT NULL,
                active_generation TEXT, invalidated INTEGER NOT NULL DEFAULT 0 CHECK(invalidated IN (0,1)),
                display_name TEXT NOT NULL DEFAULT '', input_sha256 TEXT NOT NULL DEFAULT '',
                model_sha256 TEXT NOT NULL DEFAULT '', max_input_tokens INTEGER NOT NULL DEFAULT 0,
                chunk_count INTEGER NOT NULL DEFAULT 0 CHECK(chunk_count >= 0),
                UNIQUE(source_uri, document_uri),
                CHECK(active_generation IS NULL OR (length(input_sha256)=64 AND length(model_sha256)=64
                    AND max_input_tokens BETWEEN 1 AND 8192))
            )""".trimIndent())
            db.execSQL("""CREATE TABLE chunks (
                document_id INTEGER NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                generation TEXT NOT NULL, ordinal INTEGER NOT NULL CHECK(ordinal >= 0),
                text TEXT NOT NULL, input_tokens INTEGER NOT NULL CHECK(input_tokens > 0),
                vector BLOB NOT NULL CHECK(typeof(vector)='blob' AND length(vector)=2048),
                PRIMARY KEY(document_id, generation, ordinal)
            )""".trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion != 1 || newVersion != 2) throw SQLiteException("Index schema migration required")
            db.execSQL("ALTER TABLE documents RENAME TO previous_documents")
            db.execSQL("ALTER TABLE chunks RENAME TO previous_chunks")
            onCreate(db)
            db.rawQuery("SELECT * FROM previous_documents WHERE active_generation IS NOT NULL", null).use { cursor ->
                while (cursor.moveToNext()) {
                    val modelHash = cursor.text("embedding_fingerprint").split('/', limit = 4).getOrNull(2)
                    if (modelHash == null || !modelHash.matches(Regex("[0-9a-f]{64}"))) {
                        throw SQLiteException("Cannot identify stored embedding model")
                    }
                    db.insertOrThrow("documents", null, ContentValues().apply {
                        put("id", cursor.long("id")); put("source_uri", cursor.text("source_uri"))
                        put("document_uri", cursor.text("document_uri")); put("active_generation", cursor.text("active_generation"))
                        put("display_name", cursor.text("display_name")); put("input_sha256", cursor.text("input_sha256"))
                        put("model_sha256", modelHash); put("max_input_tokens", cursor.int("max_input_tokens"))
                        put("chunk_count", cursor.int("chunk_count"))
                    })
                }
            }
            db.execSQL("""INSERT INTO chunks(document_id,generation,ordinal,text,input_tokens,vector)
                SELECT c.document_id,c.generation,c.ordinal,c.text,c.input_tokens,c.vector
                FROM previous_chunks c JOIN documents d ON d.id=c.document_id AND d.active_generation=c.generation""".trimIndent())
            db.execSQL("DROP TABLE previous_chunks")
            db.execSQL("DROP TABLE previous_documents")
        }
    }

    init {
        synchronized(ExtractionWork.sourceLock) {
            try {
                if (file == databaseFile(context)) movePreviousDirectory(context)
                if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw IOException("Cannot create index directory")
                importInvalidationMarkers()
                recover()
            } catch (error: Throwable) {
                helper.close()
                throw error
            }
        }
    }

    internal data class Ticket(val source: SelectedSource, val entry: BrowserEntry, val epoch: Long, val request: Long)
    internal data class Candidate(val ticket: Ticket, val documentId: Long, val generation: String)
    internal data class Active(val documentId: Long, val generation: String, val identity: IndexIdentity, val count: Int)

    fun request(source: SelectedSource, entry: BrowserEntry): Ticket = synchronized(ExtractionWork.sourceLock) {
        authorize(source, entry)
        val key = source.record.uri to entry.uri
        Ticket(source, entry, epochs.getOrPut(key.first) { 0 }, ++nextRequest).also { requests[key] = it.request }
    }

    fun checkCurrent(ticket: Ticket) = synchronized(ExtractionWork.sourceLock) {
        val uri = ticket.source.record.uri
        if (epochs[uri] != ticket.epoch || requests[uri to ticket.entry.uri] != ticket.request) {
            throw CancellationException("Index request superseded or source invalidated")
        }
        ExtractionWork.requireSavedRead(context, ticket.source.record)
    }

    fun active(ticket: Ticket): Active? = synchronized(ExtractionWork.sourceLock) {
        checkCurrent(ticket)
        active(ticket.source, ticket.entry)
    }

    private fun active(source: SelectedSource, entry: BrowserEntry): Active? {
        authorize(source, entry)
        return helper.readableDatabase.rawQuery(
            "SELECT * FROM documents WHERE source_uri=? AND document_uri=? AND invalidated=0 AND active_generation IS NOT NULL",
            arrayOf(source.record.uri, entry.uri)
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Active(cursor.long("id"), cursor.text("active_generation"),
                cursor.identity().also(::validateIdentity), cursor.int("chunk_count"))
        }
    }

    /** Cached text is not an independent grant. Always recheck membership and provider access. */
    fun read(source: SelectedSource, entry: BrowserEntry): IndexSnapshot? = synchronized(ExtractionWork.sourceLock) {
        val active = active(source, entry) ?: return@synchronized null
        IndexSnapshot(active.documentId, active.generation, active.identity, buildList { forEachChunk(active) { add(it) } })
    }

    fun verify(ticket: Ticket) = synchronized(ExtractionWork.sourceLock) {
        val active = active(ticket) ?: throw IOException("Missing active index generation")
        forEachChunk(active) {}
    }

    private data class SearchDocument(val active: Active, val source: SelectedSource, val entry: BrowserEntry, val eligible: Boolean)

    /** The source lock freezes saved membership and app publications throughout scan/fetch, never native work. */
    fun search(query: FloatArray, k: Int, modelSha256: String, coroutine: CoroutineContext): List<RetrievalMatch> =
        synchronized(ExtractionWork.sourceLock) {
            val ranking = RetrievalRanking(query, k)
            val sources = SourceStore(context).readForRetrieval().associateBy { it.record.uri }
            val documents = mutableMapOf<Long, SearchDocument>()
            val db = helper.readableDatabase
            db.rawQuery("SELECT * FROM documents WHERE invalidated=0 AND active_generation IS NOT NULL AND model_sha256=? ORDER BY id",
                arrayOf(modelSha256)).use { cursor ->
                while (cursor.moveToNext()) {
                    coroutine.ensureActive()
                    val source = sources[cursor.text("source_uri")] ?: continue
                    val active = Active(cursor.long("id"), cursor.text("active_generation"),
                        cursor.identity().also(::validateIdentity), cursor.int("chunk_count"))
                    val entry = BrowserEntry(source.record.uri, cursor.text("document_uri"),
                        SourceMetadata(active.identity.displayName, null, null, null), false)
                    val document = SearchDocument(active, source, entry, canRead(source, entry))
                    documents[active.documentId] = document
                    if (document.eligible) forEachChunk(active) { chunk ->
                        coroutine.ensureActive()
                        ranking.offer(active.documentId, active.generation, chunk.ordinal, chunk.vector)
                    }
                }
            }
            val matches = ranking.ranked().map { winner ->
                coroutine.ensureActive()
                val document = documents.getValue(winner.documentId)
                db.rawQuery("SELECT text FROM chunks WHERE document_id=? AND generation=? AND ordinal=?",
                    arrayOf(winner.documentId.toString(), winner.generation, winner.ordinal.toString())).use { cursor ->
                    if (!cursor.moveToFirst()) throw IOException("Missing winning index chunk")
                    RetrievalMatch(cursor.text("text"), winner.score, winner.documentId, winner.generation, winner.ordinal,
                        document.active.identity.displayName, document.source.record.uri, document.entry.uri)
                }
            }
            val current = SourceStore(context).readForRetrieval().associateBy { it.record.uri }
            if (sources.mapValues { it.value.record.kind to it.value.available } != current.mapValues { it.value.record.kind to it.value.available }) {
                throw IOException("Retrieval source access changed; retry")
            }
            for (document in documents.values) {
                coroutine.ensureActive()
                if (canRead(current.getValue(document.source.record.uri), document.entry) != document.eligible) {
                    throw IOException("Retrieval document access changed; retry")
                }
            }
            coroutine.ensureActive()
            matches
        }

    private fun canRead(source: SelectedSource, entry: BrowserEntry): Boolean {
        if (!source.available) return false
        return try { authorize(source, entry); true }
        catch (_: SecurityException) { false }
        catch (_: FileNotFoundException) { false }
    }

    private fun forEachChunk(active: Active, consume: (IndexedChunk) -> Unit) {
        var count = 0
        helper.readableDatabase.rawQuery("SELECT * FROM chunks WHERE document_id=? AND generation=? ORDER BY ordinal",
            arrayOf(active.documentId.toString(), active.generation)).use { cursor ->
            while (cursor.moveToNext()) {
                val text = cursor.text("text")
                val tokens = cursor.int("input_tokens")
                validateChunk(text, tokens, active.identity.maxInputTokens)
                if (cursor.int("ordinal") != count) throw IOException("Corrupt index chunk order")
                consume(IndexedChunk(count++, text, tokens, decodeVector(cursor.getBlob(cursor.getColumnIndexOrThrow("vector")))))
            }
        }
        if (count != active.count) throw IOException("Incomplete active index generation")
    }

    fun stage(ticket: Ticket): Candidate = synchronized(ExtractionWork.sourceLock) {
        checkCurrent(ticket)
        val db = helper.writableDatabase
        db.insertWithOnConflict("documents", null, ContentValues().apply {
            put("source_uri", ticket.source.record.uri); put("document_uri", ticket.entry.uri)
        }, SQLiteDatabase.CONFLICT_IGNORE)
        val id = db.rawQuery("SELECT id FROM documents WHERE source_uri=? AND document_uri=?",
            arrayOf(ticket.source.record.uri, ticket.entry.uri)).use { check(it.moveToFirst()); it.getLong(0) }
        Candidate(ticket, id, UUID.randomUUID().toString()).also {
            owned += it.generation
            live.getOrPut(file.absolutePath) { mutableMapOf() }[it.generation] = id
        }
    }

    fun append(candidate: Candidate, ordinal: Int, chunk: MarkdownChunk, vector: FloatArray, maxInputTokens: Int) =
        synchronized(ExtractionWork.sourceLock) {
            checkCurrent(candidate.ticket)
            check(candidate.generation in owned)
            validateChunk(chunk.text, chunk.inputTokens, maxInputTokens)
            helper.writableDatabase.insertOrThrow("chunks", null, ContentValues().apply {
                put("document_id", candidate.documentId); put("generation", candidate.generation); put("ordinal", ordinal)
                put("text", chunk.text); put("input_tokens", chunk.inputTokens); put("vector", encodeVector(vector))
            })
        }

    /** Only this short transaction publishes the complete build. Native work is already finished. */
    fun publish(candidate: Candidate, identity: IndexIdentity, count: Int, coroutine: CoroutineContext) =
        synchronized(ExtractionWork.sourceLock) {
            check(candidate.generation in owned)
            checkCurrent(candidate.ticket)
            authorize(candidate.ticket.source, candidate.ticket.entry)
            validateIdentity(identity)
            coroutine.ensureActive()
            val db = helper.writableDatabase
            db.transaction {
                db.rawQuery("SELECT count(*), min(ordinal), max(ordinal) FROM chunks WHERE document_id=? AND generation=?",
                    arrayOf(candidate.documentId.toString(), candidate.generation)).use {
                    check(it.moveToFirst())
                    check(it.getInt(0) == count && (count == 0 || (it.getInt(1) == 0 && it.getInt(2) == count - 1)))
                }
                val values = identity.values().apply {
                    put("active_generation", candidate.generation); put("chunk_count", count); put("invalidated", 0)
                }
                check(db.update("documents", values, "id=?", arrayOf(candidate.documentId.toString())) == 1)
                coroutine.ensureActive()
            }
        }

    fun discard(candidate: Candidate) = synchronized(ExtractionWork.sourceLock) {
        try {
            helper.writableDatabase.delete("chunks", "document_id=? AND generation=? AND generation NOT IN " +
                "(SELECT active_generation FROM documents WHERE id=? AND active_generation IS NOT NULL)",
                arrayOf(candidate.documentId.toString(), candidate.generation, candidate.documentId.toString()))
        } finally {
            owned -= candidate.generation
            live[file.absolutePath]?.remove(candidate.generation)
        }
    }

    fun retireObsolete() = synchronized(ExtractionWork.sourceLock) { recover() }

    private fun recover() {
        val db = helper.writableDatabase
        db.delete("documents", "invalidated=1", null)
        val abandoned = db.rawQuery("SELECT DISTINCT c.document_id, c.generation FROM chunks c JOIN documents d ON d.id=c.document_id " +
            "WHERE d.active_generation IS NULL OR c.generation != d.active_generation", null).use { cursor -> buildList {
            while (cursor.moveToNext()) if (cursor.getString(1) !in live[file.absolutePath].orEmpty()) {
                add(cursor.getLong(0).toString() to cursor.getString(1))
            }
        } }
        for ((document, generation) in abandoned) db.delete("chunks", "document_id=? AND generation=?", arrayOf(document, generation))
        val empty = db.rawQuery("SELECT id FROM documents WHERE active_generation IS NULL", null).use { cursor -> buildList {
            while (cursor.moveToNext()) if (cursor.getLong(0) !in live[file.absolutePath].orEmpty().values) add(cursor.getLong(0).toString())
        } }
        empty.forEach { db.delete("documents", "id=?", arrayOf(it)) }
    }

    private fun importInvalidationMarkers() {
        if (file != databaseFile(context)) return
        val directory = File(file.parentFile, "invalidated")
        val markers = directory.listFiles().orEmpty()
        if (markers.isEmpty()) return
        val db = helper.writableDatabase
        val uris = db.rawQuery("SELECT DISTINCT source_uri FROM documents", null).use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        } }
        db.transaction {
            for (uri in uris) if (markers.any { it.name == sourceKey(uri) }) {
                db.update("documents", ContentValues().apply { put("invalidated", 1) }, "source_uri=?", arrayOf(uri))
            }
        }
        for (marker in markers) if (!marker.delete()) throw IOException("Cannot retire previous index invalidation")
        directory.delete()
    }

    private fun authorize(source: SelectedSource, entry: BrowserEntry) {
        ExtractionWork.requireSavedRead(context, source.record)
        DocumentBrowser(context.contentResolver).authorize(source, entry)
    }

    override fun close() = synchronized(ExtractionWork.sourceLock) {
        owned.forEach { live[file.absolutePath]?.remove(it) }
        owned.clear()
        helper.close()
    }

    companion object {
        private val epochs = mutableMapOf<String, Long>()
        private val requests = mutableMapOf<Pair<String, String>, Long>()
        private val live = mutableMapOf<String, MutableMap<String, Long>>()
        private var nextRequest = 0L
        fun databaseFile(context: Context) = File(context.noBackupFilesDir, "index/index.db")
        private fun previousRoot(context: Context) = File(context.noBackupFilesDir, "index-v1")
        private fun exists(context: Context) = databaseFile(context).exists() || File(previousRoot(context), "index.db").exists()
        private fun sourceKey(uri: String) = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(Charsets.UTF_8)).hex()

        private fun movePreviousDirectory(context: Context) {
            val previous = previousRoot(context)
            if (!previous.exists()) return
            val current = databaseFile(context).parentFile!!
            if (current.exists() || !previous.renameTo(current)) throw IOException("Cannot relocate existing index")
            val fd = Os.open(context.noBackupFilesDir.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        }

        /** Commit invalidation before changing saved membership or releasing a grant. */
        fun invalidate(context: Context, uri: String) = synchronized(ExtractionWork.sourceLock) {
            epochs[uri] = (epochs[uri] ?: 0) + 1
            requests.keys.removeAll { it.first == uri }
            if (!exists(context)) return@synchronized
            IndexStore(context).use { store ->
                store.helper.writableDatabase.update("documents", ContentValues().apply { put("invalidated", 1) }, "source_uri=?", arrayOf(uri))
            }
        }

        fun reconcile(context: Context, sourceUris: Set<String>) = synchronized(ExtractionWork.sourceLock) {
            if (!exists(context)) return@synchronized
            IndexStore(context).use { store ->
                val db = store.helper.writableDatabase
                val removed = db.rawQuery("SELECT DISTINCT source_uri FROM documents", null).use { cursor -> buildList {
                    while (cursor.moveToNext()) cursor.getString(0).takeIf { it !in sourceUris }?.let(::add)
                } }
                for (uri in removed) {
                    epochs[uri] = (epochs[uri] ?: 0) + 1
                    requests.keys.removeAll { it.first == uri }
                    db.update("documents", ContentValues().apply { put("invalidated", 1) }, "source_uri=?", arrayOf(uri))
                }
                store.recover()
            }
        }
    }
}

private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String) = getInt(getColumnIndexOrThrow(name))
private fun Cursor.long(name: String) = getLong(getColumnIndexOrThrow(name))
private fun Cursor.identity() = IndexIdentity(text("display_name"), text("input_sha256"), text("model_sha256"), int("max_input_tokens"))

private fun IndexIdentity.values() = ContentValues().apply {
    put("display_name", displayName); put("input_sha256", inputSha256)
    put("model_sha256", modelSha256); put("max_input_tokens", maxInputTokens)
}

internal fun validateIdentity(identity: IndexIdentity) {
    require(identity.inputSha256.matches(Regex("[0-9a-f]{64}")) && identity.modelSha256.matches(Regex("[0-9a-f]{64}")))
    require(identity.maxInputTokens in 1..8192)
}

private fun validateChunk(text: String, inputTokens: Int, maxInputTokens: Int) {
    if (text.isBlank() || '\u0000' in text || inputTokens !in 1..maxInputTokens ||
        text.toByteArray(Charsets.UTF_8).size + EMBEDDING_DOCUMENT_PREFIX.length > EMBEDDING_INPUT_BYTES) throw IOException("Invalid index chunk")
}

internal fun encodeVector(vector: FloatArray): ByteArray {
    validateVector(vector)
    return ByteBuffer.allocate(EmbeddingSession.DIMENSIONS * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
        vector.forEach { putFloat(it) }
    }.array()
}

internal fun decodeVector(bytes: ByteArray): FloatArray {
    if (bytes.size != EmbeddingSession.DIMENSIONS * 4) throw IOException("Invalid index vector length")
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(EmbeddingSession.DIMENSIONS) { buffer.float }.also(::validateVector)
}

internal fun validateVector(vector: FloatArray) {
    if (vector.size != EmbeddingSession.DIMENSIONS || vector.any { !it.isFinite() } ||
        abs(vector.sumOf { it.toDouble() * it } - 1.0) > 1e-5) throw IOException("Invalid normalized index vector")
}

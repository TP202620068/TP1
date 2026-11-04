package com.example.tp1.indexing

import android.content.Intent
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.app.Application
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.extraction.DocumentExtractor
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.extraction.ExtractedDocument
import com.example.tp1.extraction.ExtractionFailure
import com.example.tp1.extraction.MarkdownChunk
import com.example.tp1.extraction.NativeConversion
import com.example.tp1.extraction.hex
import com.example.tp1.chat.CHAT_SYSTEM_PROMPT
import com.example.tp1.chat.ChatConnection
import com.example.tp1.chat.ChatPreparation
import com.example.tp1.chat.ChatViewModel
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.inference.InferenceSession
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DocumentIndexTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val provider = Uri.parse("content://com.example.tp1.test.documents")
    private val sources = SourceStore(context)
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private lateinit var source: SelectedSource
    private lateinit var entry: BrowserEntry
    private val selected = mutableSetOf<String>()
    private var markdown = "A warranty lasts two years. 日本語 😀"
    private var opens = 0
    private var embeds = 0
    private var closes = 0
    private var extractions = 0

    private fun fixture(method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        return try { checkNotNull(resolver.call(provider, method, arg, extras)) }
        finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun configure(bytes: ByteArray = "{\\rtf1\\ansi Fixture}".toByteArray(), name: String = "index.rtf"): Uri =
        Uri.parse(fixture("fixture-extraction", extras = Bundle().apply {
            putByteArray("bytes", bytes); putString("name", name); putString("mime", "application/rtf"); putLong("size", -1)
        }).getString("uri"))

    private fun select(uri: Uri, kind: SourceKind = SourceKind.Document): SelectedSource {
        selected += uri.toString()
        return sources.add(sources.restore().sources, listOf(uri.toString()), kind, flags).sources.single { it.record.uri == uri.toString() }
    }

    @Before fun prepare() {
        source = select(configure())
        entry = DocumentBrowser(resolver).root(source)
    }

    @After fun cleanup() {
        for (uri in selected) sources.remove(sources.restore().sources, uri)
        // Reopen to exercise durable deletion, without clearing other sources or app data.
        IndexStore(context).close()
    }

    private fun vector() = FloatArray(512).apply { this[0] = 1f }
    private fun passage(text: String = markdown) = MarkdownChunk(text, 0, text.toByteArray().size, 0,
        text.toByteArray().size, text.codePointCount(0, text.length), 12)

    private val extractor get() = DocumentExtractor(context) { _, _, output, _ ->
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        output.writeText(markdown, Charsets.UTF_8)
        NativeConversion("ok", "", "rtf", longArrayOf(), 0)
    }

    // Deliberate orchestration stand-in, not a tokenizer or real-model numerical claim.
    private fun indexer(beforeEmbed: suspend () -> Unit = {}, close: suspend () -> Unit = {}, limit: Int = 1024) =
        DocumentIndexer(context, maxInputTokens = limit, extract = { selected, document ->
            extractions++
            configure(markdown.toByteArray(Charsets.UTF_8))
            extractor.extract(selected, document)
        }, openEmbeddings = {
            assertTrue("Extraction files must be gone before model loading", ExtractionWork.operations(context).listFiles().orEmpty().isEmpty())
            opens++
            IndexEmbeddings({ text -> if (text.isBlank()) emptyList() else listOf(passage(text)) }, {
                embeds++; beforeEmbed(); vector()
            }, { closes++; close() })
        })

    private fun read() = IndexStore(context).use { it.read(source, entry) }
    private fun complete(result: IndexResult) = result as IndexResult.Complete

    @Test fun persistsOnlyChunkTextVectorsAndNeededIdentityAfterReopen() = runBlocking<Unit> {
        val result = complete(indexer().index(source, entry))
        val saved = checkNotNull(read())
        assertEquals(result.documentId, saved.documentId)
        assertEquals(result.generation, saved.generation)
        assertEquals(markdown, saved.chunks.single().text)
        assertEquals(passage().inputTokens, saved.chunks.single().inputTokens)
        assertArrayEquals(vector(), saved.chunks.single().vector, 0f)
        assertEquals(1024, saved.identity.maxInputTokens)
        assertEquals("f243c19458bcd8162632b89bfeaf02b077b24c2d812e5ece1b73bd380732ba29", saved.identity.modelSha256)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(markdown.toByteArray()).hex(), saved.identity.inputSha256)
        assertTrue(IndexStore.databaseFile(context).canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + "/"))
        assertTrue(ExtractionWork.operations(context).listFiles().orEmpty().isEmpty())
        assertFalse(File(context.noBackupFilesDir, "extraction-v1").exists())
        SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT * FROM documents LIMIT 0", null).use {
                assertFalse(it.columnNames.any { name -> "recipe" in name || "markdown" in name || "fingerprint" in name })
            }
            db.rawQuery("SELECT * FROM chunks LIMIT 0", null).use {
                assertFalse(it.columnNames.any { name -> "byte" in name || "core" in name })
            }
        }
        assertEquals(1, closes)
    }

    @Test fun snapshotReuseAndUnchangedRefreshAvoidExtractionAndModelWork() = runBlocking<Unit> {
        val indexer = indexer()
        val first = complete(indexer.index(source, entry))
        assertTrue(complete(indexer.index(source, entry)).unchanged)
        val refreshed = complete(indexer.index(source, entry, refresh = true))
        assertTrue(refreshed.unchanged)
        assertEquals(first.generation, refreshed.generation)
        assertEquals(1, opens)
        assertEquals(1, embeds)
        assertEquals(2, extractions)
    }

    @Test fun changedMarkdownAndProcessingSettingsReplaceWithoutChangingDocumentId() = runBlocking<Unit> {
        val indexer = indexer()
        val first = complete(indexer.index(source, entry))
        markdown = "Replacement text with different content."
        val second = complete(indexer.index(source, entry, refresh = true))
        assertEquals(first.documentId, second.documentId)
        assertNotEquals(first.generation, second.generation)
        assertEquals(markdown, read()!!.chunks.single().text)
        val changedLimit = complete(indexer(limit = 64).index(source, entry))
        assertFalse(changedLimit.unchanged)
        assertEquals(64, read()!!.identity.maxInputTokens)
    }

    @Test fun differentModelChecksumIsNotReused() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE documents SET model_sha256=? WHERE id=?", arrayOf<Any>("a".repeat(64), old.documentId))
        }
        val replaced = complete(indexer().index(source, entry))
        assertFalse(replaced.unchanged)
        assertNotEquals(old.generation, replaced.generation)
        assertEquals(2, embeds)
    }

    @Test fun successfulEmptyReplacesOldChunksButExtractionFailureDoesNot() = runBlocking<Unit> {
        val first = complete(indexer().index(source, entry))
        val failed = DocumentIndexer(context, extract = { _, _ -> throw ExtractionFailure("needsOcr", "Synthetic failure") })
        try { failed.index(source, entry, refresh = true); fail("Extraction failure ignored") }
        catch (error: ExtractionFailure) { assertEquals("needsOcr", error.code) }
        assertEquals(first.generation, read()!!.generation)
        markdown = " \n"
        val empty = complete(indexer().index(source, entry, refresh = true))
        assertEquals(0, empty.chunks)
        assertTrue(read()!!.chunks.isEmpty())
        assertNotEquals(first.generation, empty.generation)
        assertTrue(complete(indexer().index(source, entry)).unchanged)
        assertEquals(2, opens)
    }

    @Test fun nativeFailureAndCancelledReplacementPreserveOldGenerationAndClose() = runBlocking<Unit> {
        val previous = complete(indexer().index(source, entry))
        markdown = "Changed native failure fixture"
        try { indexer(beforeEmbed = { throw IOException("Synthetic native failure") }).index(source, entry, true); fail("Failure ignored") }
        catch (_: IOException) { }
        assertEquals(previous.generation, read()!!.generation)
        val entered = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.IO) {
            indexer(beforeEmbed = { entered.complete(Unit); blocked.await() }).index(source, entry, refresh = true)
        }
        withTimeout(5_000) { entered.await() }
        pending.cancelAndJoin()
        assertEquals(previous.generation, read()!!.generation)
        assertEquals(opens, closes)
    }

    @Test fun cancellationAfterCommitWaitsForCloseAndDoesNotErasePublishedGeneration() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        markdown = "Committed replacement"
        val closing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.IO) {
            indexer(close = { closing.complete(Unit); release.await() }).index(source, entry, true)
        }
        try {
            withTimeout(5_000) { closing.await() }
            assertNotEquals(old.generation, read()!!.generation)
            pending.cancel()
            assertFalse(pending.isCompleted)
        } finally {
            release.complete(Unit)
            pending.cancelAndJoin()
        }
        assertEquals(markdown, read()!!.chunks.single().text)
    }

    @Test fun oldGenerationRemainsVisibleWhileCandidateIsStagedAndAcrossAnotherOpen() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        IndexStore(context).use { store ->
            val ticket = store.request(source, entry)
            val candidate = store.stage(ticket)
            store.append(candidate, 0, passage("Hidden candidate"), vector(), 1024)
            assertEquals(old.generation, read()!!.generation)
            store.publish(candidate, read()!!.identity, 1, currentCoroutineContext())
            store.discard(candidate)
            assertEquals("Hidden candidate", read()!!.chunks.single().text)
        }
    }

    @Test fun abandonedUnpublishedRowsAreRecoveredOnReopen() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        val store = IndexStore(context)
        val candidate = store.stage(store.request(source, entry))
        store.append(candidate, 0, passage("Abandoned candidate"), vector(), 1024)
        store.close()
        assertEquals(old.generation, read()!!.generation)
        SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT count(*) FROM chunks WHERE generation=?", arrayOf(candidate.generation)).use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun newerRequestPreventsDelayedPublication() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        markdown = "New request fixture"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.IO) { indexer(beforeEmbed = { entered.complete(Unit); release.await() }).index(source, entry, true) }
        withTimeout(5_000) { entered.await() }
        IndexStore(context).use { it.request(source, entry) }
        release.complete(Unit)
        try { pending.await(); fail("Stale request published") } catch (_: CancellationException) { }
        assertEquals(old.generation, read()!!.generation)
    }

    @Test fun standaloneExtractionDoesNotPublishOrChangeTheIndexedSnapshot() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        markdown = "Superseding Markdown"
        assertEquals(markdown, extractor.extract(source, entry).markdown)
        assertEquals(old.generation, read()!!.generation)
        assertTrue(complete(indexer().index(source, entry)).unchanged)
        assertFalse(complete(indexer().index(source, entry, refresh = true)).unchanged)
    }

    @Test fun invalidMetadataAndSelectionFailBeforeModelOpen() = runBlocking<Unit> {
        val invalid = DocumentIndexer(context, extract = { _, _ -> ExtractedDocument(markdown, "fixture", "invalid-hash") },
            openEmbeddings = { opens++; error("Invalid metadata reached model loading") })
        try { invalid.index(source, entry); fail("Invalid metadata accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(0, opens)
        for (bad in listOf(entry.copy(uri = entry.uri + "-sibling"), entry.copy(sourceUri = "content://other"), entry.copy(directory = true))) {
            try { indexer().index(source, bad); fail("Invalid selection accepted") } catch (_: SecurityException) { }
        }
    }

    @Test fun revokedAccessDeniesCachedReadAndPublication() = runBlocking<Unit> {
        indexer().index(source, entry)
        markdown = "Revocation fixture"
        try {
            indexer(beforeEmbed = { fixture("fixture-extraction-revoke") }).index(source, entry, true)
            fail("Revoked result published")
        } catch (_: SecurityException) { }
        assertThrows(SecurityException::class.java) { read() }
    }

    @Test fun removalAndReaddCannotReviveActiveOrPendingRows() = runBlocking<Unit> {
        indexer().index(source, entry)
        markdown = "Removed pending fixture"
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = async(Dispatchers.IO) { indexer(beforeEmbed = { entered.complete(Unit); release.await() }).index(source, entry, true) }
        withTimeout(5_000) { entered.await() }
        sources.remove(sources.restore().sources, source.record.uri)
        assertThrows(SecurityException::class.java) { read() }
        source = select(configure())
        entry = DocumentBrowser(resolver).root(source)
        release.complete(Unit)
        try { pending.await(); fail("Removed result published") } catch (_: CancellationException) { }
        assertNull(read())
        assertFalse(complete(indexer().index(source, entry)).unchanged)
    }

    @Test fun durableInvalidationSurvivesFailedDatabaseDeletionAndSameUriReadd() = runBlocking<Unit> {
        indexer().index(source, entry)
        val db = SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.execSQL("CREATE TRIGGER block_index_delete BEFORE DELETE ON documents BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            sources.remove(sources.restore().sources, source.record.uri)
            assertThrows(SQLiteException::class.java) { IndexStore(context) }
            // The same URI is saved/readable again, but cleanup failure must still fail closed.
            source = selectWithoutRestore(configure())
            assertThrows(SQLiteException::class.java) { read() }
        } finally { db.execSQL("DROP TRIGGER block_index_delete"); db.close() }
        assertNull(read())
    }

    @Test fun failedInvalidationKeepsSavedMembershipAndReadGrant() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER fail_invalidation BEFORE UPDATE OF invalidated ON documents BEGIN SELECT RAISE(ABORT, 'fixture'); END")
            try {
                assertThrows(SQLiteException::class.java) { sources.remove(sources.restore().sources, source.record.uri) }
                assertTrue(sources.restore().sources.any { it.record.uri == source.record.uri })
                assertTrue(resolver.persistedUriPermissions.any { it.isReadPermission && it.uri.toString() == source.record.uri })
                assertEquals(old.generation, read()!!.generation)
            } finally { db.execSQL("DROP TRIGGER fail_invalidation") }
        }
    }

    @Test fun interruptedRemovalCannotReuseIndexedTextWhenUnknownRecordsSkipRecovery() = runBlocking<Unit> {
        indexer().index(source, entry)
        val interrupted = AssertionError("Synthetic process death after source-record commit")
        val storage = object : AtomicFile(File(context.filesDir, "sources.json")) {
            override fun finishWrite(stream: FileOutputStream?) {
                super.finishWrite(stream)
                // Bypass exception cleanup, as if the process died immediately after committing.
                throw interrupted
            }
        }
        val store = SourceStore(context, storage = storage)
        val existing = store.restore().sources
        assertSame(interrupted, assertThrows(AssertionError::class.java) { store.remove(existing, source.record.uri) })
        source = selectWithoutRestore(configure())
        entry = DocumentBrowser(resolver).root(source)
        assertNull(read())
        markdown = "Fresh Markdown after interrupted removal"
        assertFalse(complete(indexer().index(source, entry)).unchanged)
        assertEquals(markdown, read()!!.chunks.single().text)
    }

    private fun selectWithoutRestore(uri: Uri): SelectedSource {
        val store = SourceStore(context)
        // Unknown records deliberately prevent source pruning, as in a future-schema restore.
        val records = File(context.filesDir, "sources.json")
        val array = org.json.JSONArray(records.readText()).put(org.json.JSONObject().put("future", "index-fixture"))
        records.writeText(array.toString())
        try { return store.add(store.restore().sources, listOf(uri.toString()), SourceKind.Document, flags).sources.single { it.record.uri == uri.toString() } }
        finally {
            val saved = org.json.JSONArray(records.readText())
            val clean = org.json.JSONArray()
            for (i in 0 until saved.length()) if ((saved.optJSONObject(i)?.optString("future")) != "index-fixture") clean.put(saved.get(i))
            records.writeText(clean.toString())
        }
    }

    @Test fun independentlySelectedOverlappingSourcesKeepIdenticalContentSeparate() = runBlocking<Unit> {
        val first = complete(indexer().index(source, entry))
        val treeUri = Uri.parse(fixture("fixture-authorize").getString("uri"))
        val tree = select(treeUri, SourceKind.Tree)
        val child = entry.copy(sourceUri = tree.record.uri,
            uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "extract").toString())
        val second = complete(indexer().index(tree, child))
        assertNotEquals(first.documentId, second.documentId)
        assertEquals(read()!!.identity.inputSha256, IndexStore(context).use { it.read(tree, child) }!!.identity.inputSha256)
        sources.remove(sources.restore().sources, source.record.uri)
        assertEquals(second.generation, IndexStore(context).use { it.read(tree, child) }!!.generation)
    }

    @Test fun schemaConstraintsAndCorruptVectorReadsFail() = runBlocking<Unit> {
        indexer().index(source, entry)
        SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            assertThrows(SQLiteException::class.java) { db.execSQL("INSERT INTO documents(source_uri,document_uri) VALUES(?,?)", arrayOf(source.record.uri, entry.uri)) }
            assertThrows(SQLiteException::class.java) { db.execSQL("INSERT INTO chunks SELECT * FROM chunks LIMIT 1") }
            assertThrows(SQLiteException::class.java) { db.execSQL("UPDATE chunks SET vector=?", arrayOf(byteArrayOf(0))) }
            assertThrows(SQLiteException::class.java) { db.execSQL("UPDATE chunks SET document_id=-1") }
            val corrupt = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(Float.NaN).array()
            db.execSQL("UPDATE chunks SET vector=? WHERE document_id=?", arrayOf(corrupt, read()!!.documentId))
        }
        assertThrows(IOException::class.java) { read() }
        try { indexer().index(source, entry); fail("Corrupt active vector reused") } catch (_: IOException) { }
        assertEquals(1, opens)
    }

    @Test fun databaseFailureAndCancellationBeforeCommitRollBackButAfterCommitKeepsPublication() = runBlocking<Unit> {
        val old = complete(indexer().index(source, entry))
        IndexStore(context).use { store ->
            val candidate = store.stage(store.request(source, entry))
            store.append(candidate, 0, passage("Replacement"), vector(), 1024)
            val cancelled = kotlinx.coroutines.Job().apply { cancel() }
            assertThrows(CancellationException::class.java) {
                store.publish(candidate, read()!!.identity, 1, cancelled)
            }
            assertEquals(old.generation, read()!!.generation)
            SQLiteDatabase.openDatabase(IndexStore.databaseFile(context).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("CREATE TRIGGER fail_publish BEFORE UPDATE OF active_generation ON documents BEGIN SELECT RAISE(ABORT, 'fixture'); END")
                try {
                    assertThrows(SQLiteException::class.java) { store.publish(candidate, read()!!.identity, 1, kotlin.coroutines.EmptyCoroutineContext) }
                } finally { db.execSQL("DROP TRIGGER fail_publish") }
            }
            assertEquals(old.generation, read()!!.generation)
            store.publish(candidate, read()!!.identity, 1, currentCoroutineContext())
            cancelled.cancel()
            store.discard(candidate)
            assertEquals(candidate.generation, read()!!.generation)
        }
    }

    @Test fun unknownSchemaVersionIsNotDestructivelyRecreated() {
        val file = File(context.noBackupFilesDir, "index-schema-${UUID.randomUUID()}.db")
        try {
            IndexStore(context, file).close()
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { it.version = 99 }
            assertThrows(SQLiteException::class.java) { IndexStore(context, file) }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(99, it.version) }
        } finally { SQLiteDatabase.deleteDatabase(file) }
    }

    private fun previousDatabase(file: File, fingerprint: String = "model/123/f243c19458bcd8162632b89bfeaf02b077b24c2d812e5ece1b73bd380732ba29/details") {
        check(file.parentFile!!.mkdirs())
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("""CREATE TABLE documents(id INTEGER PRIMARY KEY,source_uri TEXT,document_uri TEXT,
                active_generation TEXT,display_name TEXT,input_sha256 TEXT,markdown_sha256 TEXT,
                extraction_recipe TEXT,chunking_recipe TEXT,core_tokens INTEGER,context_tokens INTEGER,
                max_input_tokens INTEGER,embedding_fingerprint TEXT,chunk_count INTEGER)""".trimIndent())
            db.execSQL("""CREATE TABLE chunks(document_id INTEGER REFERENCES documents(id) ON DELETE CASCADE,
                generation TEXT,ordinal INTEGER,text TEXT,start_byte INTEGER,end_byte INTEGER,
                core_start_byte INTEGER,core_end_byte INTEGER,core_characters INTEGER,input_tokens INTEGER,vector BLOB)""".trimIndent())
            db.insertOrThrow("documents", null, ContentValues().apply {
                put("id", 37); put("source_uri", source.record.uri); put("document_uri", entry.uri)
                put("active_generation", "complete-build"); put("display_name", "Existing document")
                put("input_sha256", "a".repeat(64)); put("markdown_sha256", "b".repeat(64))
                put("extraction_recipe", "previous extraction"); put("chunking_recipe", "previous chunking")
                put("core_tokens", 224); put("context_tokens", 16); put("max_input_tokens", 1024)
                put("embedding_fingerprint", fingerprint); put("chunk_count", 1)
            })
            for (generation in listOf("complete-build", "unfinished-build")) {
                db.insertOrThrow("chunks", null, ContentValues().apply {
                    put("document_id", 37); put("generation", generation); put("ordinal", 0)
                    put("text", markdown); put("start_byte", 0); put("end_byte", markdown.toByteArray().size)
                    put("core_start_byte", 0); put("core_end_byte", markdown.toByteArray().size)
                    put("core_characters", markdown.codePointCount(0, markdown.length)); put("input_tokens", 12)
                    put("vector", ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN).putFloat(1f).array())
                })
            }
            db.version = 1
        }
    }

    @Test fun migrationPreservesCompletedChunksAndDropsObsoleteMetadataAndCandidates() {
        val directory = File(context.noBackupFilesDir, "migration-${UUID.randomUUID()}")
        val file = File(directory, "index.db")
        try {
            previousDatabase(file)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("""INSERT INTO documents SELECT 38,source_uri,document_uri || '-empty','empty-build',
                    display_name,input_sha256,markdown_sha256,extraction_recipe,chunking_recipe,core_tokens,
                    context_tokens,max_input_tokens,embedding_fingerprint,0 FROM documents WHERE id=37""".trimIndent())
            }
            IndexStore(context, file).use { store ->
                val saved = checkNotNull(store.read(source, entry))
                assertEquals(37L, saved.documentId)
                assertEquals("complete-build", saved.generation)
                assertEquals("a".repeat(64), saved.identity.inputSha256)
                assertEquals(markdown, saved.chunks.single().text)
                assertArrayEquals(vector(), saved.chunks.single().vector, 0f)
            }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(2, db.version)
                db.rawQuery("SELECT * FROM documents LIMIT 0", null).use { assertFalse("markdown_sha256" in it.columnNames) }
                db.rawQuery("SELECT count(*) FROM chunks", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
                db.rawQuery("SELECT active_generation,chunk_count FROM documents WHERE id=38", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("empty-build", it.getString(0)); assertEquals(0, it.getInt(1))
                }
                db.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun failedMigrationRollsBackWithoutDestroyingExistingData() {
        val directory = File(context.noBackupFilesDir, "migration-${UUID.randomUUID()}")
        val file = File(directory, "index.db")
        try {
            previousDatabase(file, "unrecognized model")
            assertThrows(SQLiteException::class.java) { IndexStore(context, file) }
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(1, db.version)
                db.rawQuery("SELECT embedding_fingerprint FROM documents", null).use {
                    assertTrue(it.moveToFirst()); assertEquals("unrecognized model", it.getString(0))
                }
                db.rawQuery("SELECT count(*) FROM chunks", null).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun directoryMigrationImportsPendingInvalidationBeforeAnyRead() {
        val directory = File(context.noBackupFilesDir, "migration-${UUID.randomUUID()}")
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
        }
        val previous = File(directory, "index-v1")
        try {
            previousDatabase(File(previous, "index.db"))
            val markers = File(previous, "invalidated").apply { mkdirs() }
            File(markers, MessageDigest.getInstance("SHA-256").digest(source.record.uri.toByteArray()).hex()).writeText("1")
            IndexStore(isolated).use { assertNull(it.read(source, entry)) }
            assertFalse(previous.exists())
            assertTrue(IndexStore.databaseFile(isolated).isFile)
            assertFalse(File(IndexStore.databaseFile(isolated).parentFile, "invalidated").exists())
        } finally { directory.deleteRecursively() }
    }

    @Test @ManualInference fun authorizedAnyDocRtfThroughProductionChunkerAndPinnedJinaPersistsAfterReopen() = runBlocking<Unit> {
        val models = ModelManager(context)
        val owners = ViewModelStore()
        val chat = withContext(Dispatchers.Main) {
            ChatViewModel(context.applicationContext as Application, SavedStateHandle(), {}, {
                val loaded = InferenceSession.open(models, Model.Test, CHAT_SYSTEM_PROMPT)
                ChatConnection(loaded::prepare, loaded::stream, loaded::cancel, loaded::closeAsync)
            }).also { owners.put("chat", it); it.setForeground(true); it.setSelected(true) }
        }
        try {
            withTimeout(120_000) {
                while (chat.state.value.preparation != ChatPreparation.Ready) {
                    assertNotEquals(ChatPreparation.Error, chat.state.value.preparation)
                    delay(25)
                }
            }
            withContext(Dispatchers.Main) { chat.setSelected(false); chat.editDraft("Retained draft") }
            val available = ExtractionWork.permit.tryLock()
            if (available) ExtractionWork.permit.unlock()
            assertFalse(available)
            val rtf = "{\\rtf1\\ansi " + "The laptop warranty lasts two years. Keep the receipt for repairs.\\par ".repeat(30) + "}"
            configure(rtf.toByteArray())
            var extracted: ExtractedDocument? = null
            val result = complete(DocumentIndexer(context, extract = { selected, document ->
                DocumentExtractor(context).extract(selected, document).also { extracted = it }
            }).index(source, entry, refresh = true))
            val saved = checkNotNull(read())
            assertTrue(saved.chunks.size > 1)
            assertEquals(result.chunks, saved.chunks.size)
            assertEquals(ChatPreparation.Unprepared, chat.state.value.preparation)
            assertEquals("Retained draft", chat.state.value.draft)
            val markdown = checkNotNull(extracted).markdown
            assertTrue(ExtractionWork.operations(context).listFiles().orEmpty().isEmpty())
            val session = EmbeddingSession.open(models)
            try {
                val expected = session.chunkDocument(markdown)
                assertEquals(expected.map { it.text }, saved.chunks.map { it.text })
                assertEquals(expected.map { it.inputTokens }, saved.chunks.map { it.inputTokens })
                for (chunk in saved.chunks) {
                    assertEquals(chunk.inputTokens, session.countDocumentTokens(chunk.text))
                    assertArrayEquals(session.embedDocument(chunk.text), chunk.vector, 0f)
                }
            } finally { session.close() }
            assertTrue(complete(DocumentIndexer(context).index(source, entry)).unchanged)
        } finally { withContext(Dispatchers.Main) { owners.clear() } }
    }
}

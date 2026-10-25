package com.example.tp1.sources

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import androidx.core.net.toUri
import com.example.tp1.extraction.ExtractionArtifacts

internal data class SourceLoad(val sources: List<SelectedSource>, val damagedRecords: Boolean)
internal data class SourceUpdate(val sources: List<SelectedSource>, val rejected: Boolean)

/** One caller serializes operations, all on Dispatchers.IO. No document contents are read. */
internal class SourceStore(
    private val context: Context,
    file: File = File(context.filesDir, "sources.json"),
    private val storage: AtomicFile = AtomicFile(file)
) {
    private val resolver = context.contentResolver
    private val managesArtifacts = file == File(context.filesDir, "sources.json")
    private var readable = false
    private var unknownRecords = emptyList<Any>()

    fun restore(): SourceLoad {
        val records = synchronized(ExtractionArtifacts.sourceLock) {
            readRecords().also { records ->
                // Unknown records may belong to a future schema; do not prune their artifacts.
                if (managesArtifacts && unknownRecords.isEmpty()) ExtractionArtifacts.reconcile(context, records.map { it.uri }.toSet())
            }
        }
        val grants = retainedReads()
        val result = SourceLoad(records.map { inspect(it, grants) }, unknownRecords.isNotEmpty())
        readable = true
        return result
    }

    private fun readRecords(): List<SourceRecord> {
        readable = false
        val text = try {
            storage.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: FileNotFoundException) {
            if (storage.baseFile.exists()) throw IOException("Cannot read source records")
            "[]"
        }
        val array = JSONArray(text)
        val records = mutableListOf<SourceRecord>()
        val unknown = mutableListOf<Any>()
        for (index in 0 until array.length()) {
            val value = array.get(index)
            try {
                val item = value as JSONObject
                val record = SourceRecord(
                    item.getString("uri"),
                    SourceKind.valueOf(item.getString("kind")),
                    item.optString("name").takeIf { it.isNotBlank() }
                )
                require(validUri(record))
                records += record
            } catch (_: Exception) {
                // Keep unrecognized entries verbatim when saving other sources.
                unknown += value
            }
        }
        unknownRecords = unknown
        return records.deduplicated()
    }

    internal fun containsRecord(record: SourceRecord): Boolean = synchronized(ExtractionArtifacts.sourceLock) {
        readRecords().any { it.uri == record.uri && it.kind == record.kind }
    }

    internal fun writeRecords(records: List<SourceRecord>) = synchronized(ExtractionArtifacts.sourceLock) {
        check(readable) { "Restore must succeed before writing" }
        val array = JSONArray()
        records.deduplicated().forEach { record ->
            array.put(JSONObject().put("uri", record.uri).put("kind", record.kind.name).apply {
                record.name?.let { put("name", it) }
            })
        }
        unknownRecords.forEach(array::put)
        val bytes = array.toString().toByteArray(Charsets.UTF_8)
        // Until verification succeeds, callers must restore disk state before another mutation.
        readable = false
        val stream = storage.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            storage.finishWrite(stream)
        } catch (error: Exception) {
            storage.failWrite(stream)
            throw error
        }
        if (!storage.openRead().use { it.readBytes() }.contentEquals(bytes)) {
            throw IOException("Source records were not saved")
        }
        readable = true
    }

    fun add(
        existing: List<SelectedSource>,
        uris: List<String>,
        kind: SourceKind,
        offeredFlags: Int
    ): SourceUpdate {
        check(readable) { "Restore must succeed before changing sources" }
        val previousGrants = retainedReads()
        val acquired = mutableSetOf<String>()
        val next = existing.toMutableList()
        var rejected = false
        var changed = false
        try {
            for (text in uris.distinct()) {
                val record = SourceRecord(text, kind)
                try {
                    require(validUri(record))
                    val index = next.indexOfFirst { it.record.uri == text }
                    if (index >= 0 && next[index].available) continue
                    val required = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    require(offeredFlags and required == required)
                    resolver.takePersistableUriPermission(text.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (text !in previousGrants) acquired += text
                    check(text in retainedReads())
                    val selected = inspect(record, setOf(text))
                    check(selected.available)
                    val named = selected.copy(record = record.copy(name = selected.metadata?.name))
                    if (index >= 0) next[index] = named else next += named
                    changed = true
                } catch (_: Exception) {
                    rejected = true
                }
            }
            if (changed) writeRecords(next.map { it.record })
        } catch (error: Exception) {
            acquired.forEach(::releaseRead)
            throw error
        }
        // Release only grants acquired by this operation that did not become saved sources.
        acquired.filter { uri -> next.none { it.record.uri == uri && it.available } }
            .forEach(::releaseRead)
        return SourceUpdate(next, rejected)
    }

    fun remove(existing: List<SelectedSource>, uri: String): List<SelectedSource> = synchronized(ExtractionArtifacts.sourceLock) {
        check(readable) { "Restore must succeed before changing sources" }
        val next = existing.filterNot { it.record.uri == uri }
        try {
            writeRecords(next.map { it.record })
            if (managesArtifacts) ExtractionArtifacts.removed(context, uri)
        } catch (error: Exception) {
            // Verification may fail after committing. Retire disk artifacts even if unknown records prevent reconciliation.
            readable = false
            if (managesArtifacts) {
                try {
                    ExtractionArtifacts.removed(context, uri)
                } catch (cleanup: Exception) {
                    error.addSuppressed(cleanup)
                }
            }
            throw error
        }
        if (next.none { it.record.uri == uri }) releaseRead(uri)
        next
    }

    private fun releaseRead(text: String) {
        if (unknownRecords.any { it is JSONObject && it.optString("uri") == text }) return
        try {
            resolver.releasePersistableUriPermission(text.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Already revoked/unavailable grants do not prevent removing a saved record.
        }
    }

    private fun retainedReads(): Set<String> = resolver.persistedUriPermissions
        .filter { it.isReadPermission }.map { it.uri.toString() }.toSet()

    private fun validUri(record: SourceRecord): Boolean {
        val uri = record.uri.toUri()
        return uri.scheme == "content" && !uri.authority.isNullOrBlank() && when (record.kind) {
            SourceKind.Tree -> DocumentsContract.isTreeUri(uri)
            SourceKind.Document -> DocumentsContract.getDocumentId(uri).isNotBlank()
        }
    }

    private fun inspect(record: SourceRecord, grants: Set<String>): SelectedSource {
        if (record.uri !in grants) return SelectedSource(record, null)
        return try {
            val uri = record.uri.toUri()
            val document = if (record.kind == SourceKind.Tree) {
                DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            } else uri
            val metadata = resolver.query(document, null, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                SourceMetadata(
                    name = cursor.text(Document.COLUMN_DISPLAY_NAME)?.takeIf { it.isNotBlank() }
                        ?: record.name ?: context.getString(com.example.tp1.R.string.unnamed_source),
                    mimeType = cursor.text(Document.COLUMN_MIME_TYPE),
                    size = cursor.number(Document.COLUMN_SIZE)?.takeIf { it >= 0 },
                    modified = cursor.number(Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 }
                )
            }
            SelectedSource(record, metadata)
        } catch (_: Exception) {
            SelectedSource(record, null)
        }
    }

    private fun Cursor.text(column: String): String? = getColumnIndex(column).let {
        if (it < 0 || isNull(it)) null else getString(it)
    }

    private fun Cursor.number(column: String): Long? = getColumnIndex(column).let {
        if (it < 0 || isNull(it)) null else getLong(it)
    }
}

package com.example.tp1.extraction

import android.content.Context
import android.util.Log
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceRecord
import com.example.tp1.sources.SourceStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

/** Process-wide access and native ownership. Extraction results are never stored here. */
internal object ExtractionWork {
    val sourceLock = Any()
    // Inference holds this permit for the full loaded-model lifetime, including idle periods.
    val permit = Mutex()
    private var initialized = false
    private var nextRequest = 0L
    private val epochs = mutableMapOf<String, Long>()
    private val requests = mutableMapOf<Pair<String, String>, Long>()

    internal data class Ticket(val source: SourceRecord, val documentUri: String, val epoch: Long, val request: Long)

    fun operations(context: Context) = File(context.cacheDir, "extraction")

    private fun initialize(context: Context) {
        if (initialized) return
        discard(operations(context))
        // Retire only the app's obsolete derived Markdown/JSON cache, never sources or model weights.
        val legacy = File(context.noBackupFilesDir, "extraction-v1")
        discard(legacy)
        if (legacy.exists()) throw IOException("Cannot retire obsolete extraction cache")
        initialized = true
    }

    fun reconcile(context: Context, sourceUris: Set<String>) = synchronized(sourceLock) {
        initialize(context)
        epochs.keys.filter { it !in sourceUris }.forEach(::invalidate)
    }

    fun invalidate(uri: String) = synchronized(sourceLock) {
        epochs[uri] = (epochs[uri] ?: 0) + 1
        requests.keys.removeAll { it.first == uri }
    }

    fun begin(context: Context, source: SelectedSource, entry: BrowserEntry): Ticket = synchronized(sourceLock) {
        initialize(context)
        if (!source.available || entry.directory || entry.sourceUri != source.record.uri) throw SecurityException("Invalid extraction selection")
        requireSavedRead(context, source.record)
        val request = ++nextRequest
        requests[source.record.uri to entry.uri] = request
        Ticket(source.record, entry.uri, epochs.getOrPut(source.record.uri) { 0 }, request)
    }

    fun checkCurrent(context: Context, ticket: Ticket) = synchronized(sourceLock) {
        if (epochs[ticket.source.uri] != ticket.epoch || requests[ticket.source.uri to ticket.documentUri] != ticket.request) {
            throw CancellationException("Extraction superseded or source removed")
        }
        requireSavedRead(context, ticket.source)
    }

    fun requireSavedRead(context: Context, source: SourceRecord) {
        if (!SourceStore(context).containsRecord(source) || context.contentResolver.persistedUriPermissions.none {
                it.isReadPermission && it.uri.toString() == source.uri
            }) throw SecurityException("Saved source read grant unavailable")
    }

    fun discard(file: File) {
        if (file.exists() && !file.deleteRecursively()) Log.w("TP1Extraction", "Temporary extraction cleanup deferred")
    }
}

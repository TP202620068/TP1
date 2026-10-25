package com.example.tp1.extraction

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceRecord
import com.example.tp1.sources.SourceStore
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex

/** Process-wide ownership, shared with source removal. No native code runs under sourceLock. */
internal object ExtractionArtifacts {
    val sourceLock = Any()
    val permit = Mutex()
    private var initialized = false
    private var nextRequest = 0L
    private val epochs = mutableMapOf<String, Long>()
    private val requests = mutableMapOf<Pair<String, String>, Long>()

    internal data class Ticket(val source: SourceRecord, val documentUri: String, val epoch: Long, val request: Long)

    private fun root(context: Context) = File(context.noBackupFilesDir, "extraction-v1")
    fun operations(context: Context) = File(root(context), "operations")
    private fun sources(context: Context) = File(root(context), "sources")
    private fun invalidated(context: Context) = File(root(context), "invalidated")
    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    private fun sourceDirectory(context: Context, uri: String) = File(sources(context), key(uri))
    private fun documentDirectory(context: Context, source: String, document: String) = File(sourceDirectory(context, source), key(document))

    private fun initialize(context: Context) {
        if (initialized) return
        discard(operations(context))
        sources(context).listFiles()?.filter { it.isDirectory }?.forEach { source ->
            source.listFiles()?.filter { it.isDirectory }?.forEach { document ->
                val current = currentGeneration(document)
                val generation = current?.let { File(document, it) }
                if (generation != null && File(generation, "document.md").isFile && File(generation, "provenance.json").isFile) {
                    document.listFiles()?.filter { it.isDirectory && it.name != current }?.forEach(::discard)
                }
                document.listFiles()?.filter { it.name.startsWith("current.") && it.name.endsWith(".tmp") }?.forEach(::discard)
            }
        }
        initialized = true
    }

    /** Call while restoring source records, including after an interrupted removal. */
    fun reconcile(context: Context, sourceUris: Set<String>) = synchronized(sourceLock) {
        initialize(context)
        invalidated(context).listFiles()?.forEach { marker -> cleanInvalidated(context, marker) }
        epochs.keys.filter { it !in sourceUris }.forEach { uri ->
            epochs[uri] = epochs.getValue(uri) + 1
            requests.keys.removeAll { it.first == uri }
        }
        val retained = sourceUris.map(::key).toSet()
        sources(context).listFiles()?.filter { it.name !in retained }?.forEach { retire(context, it, File::deleteRecursively) }
    }

    fun removed(context: Context, uri: String, erase: (File) -> Boolean = File::deleteRecursively) = synchronized(sourceLock) {
        invalidate(uri)
        retire(context, sourceDirectory(context, uri), erase)
    }

    private fun retire(context: Context, directory: File, erase: (File) -> Boolean) {
        val markers = invalidated(context)
        val marker = File(markers, directory.name)
        if (!directory.exists() && !marker.exists()) return
        if (!marker.exists()) {
            if (!markers.isDirectory && !markers.mkdirs()) throw IOException("Cannot record artifact invalidation")
            FileOutputStream(marker).use { stream -> stream.write(1); stream.fd.sync() }
            syncDirectory(markers)
        }
        if (!directory.exists() || erase(directory)) {
            if (marker.delete()) syncDirectory(markers)
        } else Log.w("TP1Extraction", "Invalidated artifact cleanup deferred until next startup")
    }

    private fun cleanInvalidated(context: Context, marker: File) {
        val directory = File(sources(context), marker.name)
        discard(directory)
        if (!directory.exists() && marker.delete()) syncDirectory(invalidated(context))
    }

    fun invalidate(uri: String) = synchronized(sourceLock) {
        epochs[uri] = (epochs[uri] ?: 0) + 1
        requests.keys.removeAll { it.first == uri }
    }

    fun begin(context: Context, source: SelectedSource, entry: BrowserEntry): Ticket = synchronized(sourceLock) {
        initialize(context)
        if (!source.available || entry.directory || entry.sourceUri != source.record.uri) throw SecurityException("Invalid extraction selection")
        requireSavedRead(context, source.record)
        val marker = File(invalidated(context), key(source.record.uri))
        if (marker.exists()) {
            cleanInvalidated(context, marker)
            if (marker.exists()) throw IOException("Previous source artifacts could not be removed")
        }
        val epoch = epochs.getOrPut(source.record.uri) { 0 }
        val request = ++nextRequest
        requests[source.record.uri to entry.uri] = request
        Ticket(source.record, entry.uri, epoch, request)
    }

    fun checkCurrent(context: Context, ticket: Ticket) = synchronized(sourceLock) {
        if (epochs[ticket.source.uri] != ticket.epoch || requests[ticket.source.uri to ticket.documentUri] != ticket.request) {
            throw CancellationException("Extraction superseded or source removed")
        }
        requireSavedRead(context, ticket.source)
    }

    private fun requireSavedRead(context: Context, source: SourceRecord) {
        if (!SourceStore(context).containsRecord(source) || context.contentResolver.persistedUriPermissions.none {
                it.isReadPermission && it.uri.toString() == source.uri
            }) throw SecurityException("Saved source read grant unavailable")
    }

    fun publish(context: Context, ticket: Ticket, staged: File, coroutine: CoroutineContext): ExtractionArtifact = synchronized(sourceLock) {
        checkCurrent(context, ticket)
        coroutine.ensureActive()
        val document = documentDirectory(context, ticket.source.uri, ticket.documentUri)
        if (!document.isDirectory && !document.mkdirs()) throw IOException("Cannot create artifact directory")
        val name = UUID.randomUUID().toString()
        val generation = File(document, name)
        if (!staged.renameTo(generation)) throw IOException("Cannot publish extraction generation")
        val pointer = File(document, "current.$name.tmp")
        var committed = false
        try {
            syncDirectory(generation)
            syncDirectory(document)
            coroutine.ensureActive()
            FileOutputStream(pointer).use { stream ->
                stream.write(name.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            coroutine.ensureActive()
            Files.move(pointer.toPath(), File(document, "current").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            committed = true
            syncDirectory(document)
        } catch (error: Exception) {
            if (!committed) discard(generation)
            throw error
        } finally {
            discard(pointer)
        }
        document.listFiles()?.filter { it.isDirectory && it.name != name }?.forEach(::discard)
        ExtractionArtifact(File(generation, "document.md"), File(generation, "provenance.json"))
    }

    /** Readers must recheck membership/grants rather than treating an old artifact path as authorization. */
    fun current(context: Context, source: SelectedSource, entry: BrowserEntry): ExtractionArtifact? = synchronized(sourceLock) {
        initialize(context)
        if (entry.sourceUri != source.record.uri || entry.directory) throw SecurityException("Invalid artifact selection")
        requireSavedRead(context, source.record)
        if (File(invalidated(context), key(source.record.uri)).exists()) return@synchronized null
        val document = documentDirectory(context, source.record.uri, entry.uri)
        val generation = currentGeneration(document)?.let { File(document, it) } ?: return@synchronized null
        val artifact = ExtractionArtifact(File(generation, "document.md"), File(generation, "provenance.json"))
        if (!artifact.markdown.isFile || !artifact.provenance.isFile) throw IOException("Incomplete extraction artifact")
        artifact
    }

    private fun currentGeneration(document: File): String? {
        val text = try {
            File(document, "current").inputStream().use { stream ->
                val bytes = stream.readNBytes(37)
                if (bytes.size != 36) return null
                String(bytes, Charsets.UTF_8)
            }
        } catch (_: FileNotFoundException) {
            return null
        }
        return try { UUID.fromString(text).toString().takeIf { it == text } } catch (_: IllegalArgumentException) { null }
    }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }

    fun discard(file: File) {
        if (file.exists() && !file.deleteRecursively()) Log.w("TP1Extraction", "Extraction cleanup deferred until next startup")
    }
}

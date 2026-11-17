package com.example.tp1.indexing

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.extraction.DocumentExtractor
import com.example.tp1.extraction.ExtractedDocument
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.extraction.MarkdownChunk
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.inference.DOCUMENT_EMBEDDING_DEADLINE_MILLIS
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelAcquisition
import com.example.tp1.inference.ModelManager
import com.example.tp1.inference.NativeWorkHandoff
import com.example.tp1.sources.SelectedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal sealed interface IndexResult {
    data class Complete(val documentId: Long, val generation: String, val chunks: Int, val unchanged: Boolean) : IndexResult
    data object Busy : IndexResult
}

/** Concrete function seam like ChatConnection; production delegates to the existing session. */
internal class IndexEmbeddings(
    val chunk: suspend (String) -> List<MarkdownChunk>,
    val embed: suspend (String) -> FloatArray,
    val close: suspend () -> Unit
)

/** One explicit document. Refresh reads the original again; ordinary reads reuse an authorized snapshot. */
internal class DocumentIndexer(
    context: Context,
    private val models: ModelManager = ModelManager(context),
    private val maxInputTokens: Int = 1024,
    private val extract: suspend (SelectedSource, BrowserEntry) -> ExtractedDocument = DocumentExtractor(context)::extract,
    private val openEmbeddings: suspend (Int) -> IndexEmbeddings = { limit ->
        val session = EmbeddingSession.open(models, limit)
        IndexEmbeddings(session::chunkDocument, session::embedDocument, session::close)
    },
    private val checkAdmission: () -> Unit = {},
    private val acquireModel: suspend () -> Unit = { ModelAcquisition.get(context).acquire(Model.JinaV5NanoRetrieval) }
) {
    private val context = context.applicationContext

    init { require(maxInputTokens in 1..8192) }

    suspend fun index(source: SelectedSource, entry: BrowserEntry, refresh: Boolean = false): IndexResult =
        withContext(Dispatchers.IO) {
            val diagnostics = IndexingDiagnostics(log = { message, error ->
                if (error == null) Log.i("TP1Index", message)
                else if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.e("TP1Index", message, error)
                else Log.e("TP1Index", message)
            })
            diagnostics.start("storage")
            try {
                val opened = try { IndexStore(context) }
                catch (error: java.io.IOException) { throw IndexStorageFailure(error) }
                val result = opened.use { store ->
                    val coroutine = currentCoroutineContext()
                    val ticket = synchronized(ExtractionWork.sourceLock) {
                        coroutine.ensureActive()
                        checkAdmission()
                        store.request(source, entry)
                    }
                    diagnostics.request = ticket.request
                    diagnostics.start("cache_lookup")
                    val modelHash = models.profile(Model.JinaV5NanoRetrieval).artifact.sha256
                    val cached = synchronized(ExtractionWork.sourceLock) {
                        coroutine.ensureActive()
                        store.active(ticket)?.takeIf {
                            !refresh && it.identity.modelSha256 == modelHash && it.identity.maxInputTokens == maxInputTokens
                        }?.also { store.verify(ticket) }
                    }
                    if (cached != null) return@use IndexResult.Complete(
                        cached.documentId, cached.generation, cached.count, unchanged = true)
                    diagnostics.start("model_acquisition")
                    try { acquireModel() }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { throw IndexModelFailure(error) }
                    coroutine.ensureActive()
                    checkAdmission()
                    store.checkCurrent(ticket)
                    diagnostics.start("waiting_for_native")
                    NativeWorkHandoff.documents.withLock {
                        val handoff = Any()
                        var embeddings: IndexEmbeddings? = null
                        var candidate: IndexStore.Candidate? = null
                        try {
                            diagnostics.start("handoff")
                            if (!NativeWorkHandoff.acquire(handoff)) return@withLock IndexResult.Busy
                            store.checkCurrent(ticket)
                            diagnostics.start("extraction")
                            // Extraction returns bounded text and removes its temporary files before Jina is opened.
                            val extracted = extract(source, entry)
                            diagnostics.report("extracted")
                            val identity = IndexIdentity(extracted.displayName, extracted.inputSha256, modelHash, maxInputTokens)
                            validateIdentity(identity)
                            val unchanged = synchronized(ExtractionWork.sourceLock) {
                                coroutine.ensureActive()
                                store.active(ticket)?.takeIf {
                                    it.identity == identity.copy(displayName = it.identity.displayName)
                                }?.also { store.verify(ticket) }
                            }
                            if (unchanged != null) return@withLock IndexResult.Complete(
                                unchanged.documentId, unchanged.generation, unchanged.count, unchanged = true)
                            store.checkCurrent(ticket)
                            diagnostics.start("model_loading", "maxInputTokens=$maxInputTokens")
                            embeddings = try { openEmbeddings(maxInputTokens) }
                            catch (error: CancellationException) { throw error }
                            catch (error: Exception) { throw IndexModelFailure(error) }
                            diagnostics.start("chunking")
                            val chunks = embeddings.chunk(extracted.markdown)
                            diagnostics.start("staging", "chunks=${chunks.size}")
                            candidate = store.stage(ticket)
                            for ((ordinal, chunk) in chunks.withIndex()) {
                                coroutine.ensureActive()
                                val progress = "chunk=${ordinal + 1}/${chunks.size} inputTokens=${chunk.inputTokens}"
                                diagnostics.start("embedding", "$progress deadlineMs=$DOCUMENT_EMBEDDING_DEADLINE_MILLIS")
                                val vector = embeddings.embed(chunk.text)
                                diagnostics.report("embedded")
                                coroutine.ensureActive()
                                diagnostics.start("write_chunk", progress)
                                store.append(candidate, ordinal, chunk, vector, maxInputTokens)
                            }
                            diagnostics.start("publishing", "chunks=${chunks.size}")
                            synchronized(ExtractionWork.sourceLock) {
                                checkAdmission()
                                store.publish(candidate, identity, chunks.size, coroutine)
                            }
                            IndexResult.Complete(candidate.documentId, candidate.generation, chunks.size, unchanged = false)
                        } finally {
                            withContext(NonCancellable) {
                                try {
                                    try { embeddings?.close?.invoke() }
                                    catch (error: Exception) { diagnostics.report("model_close_failed", error); throw error }
                                } finally {
                                    try {
                                        candidate?.let { store.discard(it) }
                                        store.retireObsolete()
                                    } catch (error: Exception) {
                                        diagnostics.report("cleanup_deferred", error)
                                    } finally { NativeWorkHandoff.release(handoff) }
                                }
                            }
                        }
                    }
                }
                diagnostics.report(when (result) {
                    is IndexResult.Complete -> if (result.unchanged) "unchanged" else "completed"
                    IndexResult.Busy -> "waiting_for_chat"
                })
                result
            } catch (error: CancellationException) {
                diagnostics.report("cancelled")
                throw error
            } catch (error: Exception) {
                diagnostics.report("failed", error)
                throw error
            }
        }
}

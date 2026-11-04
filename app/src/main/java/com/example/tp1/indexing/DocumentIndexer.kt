package com.example.tp1.indexing

import android.content.Context
import android.util.Log
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.extraction.DocumentExtractor
import com.example.tp1.extraction.ExtractedDocument
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.extraction.MarkdownChunk
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import com.example.tp1.inference.NativeWorkHandoff
import com.example.tp1.sources.SelectedSource
import kotlinx.coroutines.Dispatchers
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
    }
) {
    private val context = context.applicationContext

    init { require(maxInputTokens in 1..8192) }

    suspend fun index(source: SelectedSource, entry: BrowserEntry, refresh: Boolean = false): IndexResult =
        withContext(Dispatchers.IO) {
            IndexStore(context).use { store ->
                val ticket = store.request(source, entry)
                NativeWorkHandoff.documents.withLock {
                    val handoff = Any()
                    val coroutine = currentCoroutineContext()
                    var embeddings: IndexEmbeddings? = null
                    var candidate: IndexStore.Candidate? = null
                    try {
                        val modelHash = models.profile(Model.JinaV5NanoRetrieval).artifact.sha256
                        val cached = synchronized(ExtractionWork.sourceLock) {
                            coroutine.ensureActive()
                            store.active(ticket)?.takeIf {
                                !refresh && it.identity.modelSha256 == modelHash && it.identity.maxInputTokens == maxInputTokens
                            }?.also { store.verify(ticket) }
                        }
                        if (cached != null) return@withLock IndexResult.Complete(
                            cached.documentId, cached.generation, cached.count, unchanged = true)
                        if (!NativeWorkHandoff.acquire(handoff)) return@withLock IndexResult.Busy
                        store.checkCurrent(ticket)
                        // Extraction returns bounded text and removes its temporary files before Jina is opened.
                        val extracted = extract(source, entry)
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
                        embeddings = openEmbeddings(maxInputTokens)
                        val chunks = embeddings.chunk(extracted.markdown)
                        candidate = store.stage(ticket)
                        for ((ordinal, chunk) in chunks.withIndex()) {
                            coroutine.ensureActive()
                            val vector = embeddings.embed(chunk.text)
                            coroutine.ensureActive()
                            store.append(candidate, ordinal, chunk, vector, maxInputTokens)
                            Log.i("TP1Index", "chunk=$ordinal inputTokens=${chunk.inputTokens}")
                        }
                        store.publish(candidate, identity, chunks.size, coroutine)
                        IndexResult.Complete(candidate.documentId, candidate.generation, chunks.size, unchanged = false)
                    } finally {
                        withContext(NonCancellable) {
                            try {
                                embeddings?.close?.invoke()
                            } finally {
                                try {
                                    candidate?.let { store.discard(it) }
                                    store.retireObsolete()
                                } catch (_: Exception) {
                                    Log.w("TP1Index", "Generation cleanup deferred")
                                } finally { NativeWorkHandoff.release(handoff) }
                            }
                        }
                    }
                }
            }
        }
}

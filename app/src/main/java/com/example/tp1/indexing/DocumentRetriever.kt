package com.example.tp1.indexing

import android.content.Context
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import com.example.tp1.inference.NativeWorkHandoff
import com.example.tp1.inference.embeddingInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class RetrievalMatch(
    val text: String,
    val score: Double,
    val documentId: Long,
    val generation: String,
    val ordinal: Int,
    val displayName: String,
    val sourceUri: String,
    val documentUri: String
)

internal sealed interface RetrievalResult {
    data class Complete(val matches: List<RetrievalMatch>) : RetrievalResult
    data object Busy : RetrievalResult
}

/** Concrete session seam for ownership tests without loading weights. */
internal class RetrievalEmbeddings(val embed: suspend (String) -> FloatArray, val close: suspend () -> Unit)

/** Searches authorized indexed snapshots, not current provider contents. Refresh is explicit through indexing. */
internal class DocumentRetriever(
    context: Context,
    private val models: ModelManager = ModelManager(context),
    private val openEmbeddings: suspend () -> RetrievalEmbeddings = {
        val session = EmbeddingSession.open(models)
        RetrievalEmbeddings(session::embedQuery, session::close)
    }
) {
    private val context = context.applicationContext

    /** k is 1..50. Scores are cosine similarities, not confidence or an answerability threshold. */
    suspend fun retrieve(query: String, k: Int = 5): RetrievalResult = withContext(Dispatchers.IO) {
        require(k in 1..MAX_RETRIEVAL_K) { "k must be between 1 and $MAX_RETRIEVAL_K" }
        embeddingInput(query, query = true)
        NativeWorkHandoff.documents.withLock {
            val handoff = Any()
            var embeddings: RetrievalEmbeddings? = null
            var failure: Throwable? = null
            val vector = try {
                if (!NativeWorkHandoff.acquire(handoff)) return@withLock RetrievalResult.Busy
                currentCoroutineContext().ensureActive()
                embeddings = openEmbeddings()
                currentCoroutineContext().ensureActive()
                embeddings.embed(query).also(::validateVector)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                withContext(NonCancellable) {
                    try {
                        try { embeddings?.close?.invoke() }
                        catch (cleanup: Throwable) {
                            if (failure != null) failure.addSuppressed(cleanup) else throw cleanup
                        }
                    } finally { NativeWorkHandoff.release(handoff) }
                }
            }
            // No native-close or Main-thread handoff wait may separate the final access check from delivery.
            val coroutine = currentCoroutineContext()
            coroutine.ensureActive()
            synchronized(ExtractionWork.sourceLock) {
                coroutine.ensureActive()
                IndexStore(context).use { store ->
                    RetrievalResult.Complete(store.search(vector, k, models.profile(Model.JinaV5NanoRetrieval).artifact.sha256, coroutine))
                }
            }
        }
    }
}

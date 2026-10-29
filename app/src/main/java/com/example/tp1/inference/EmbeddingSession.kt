package com.example.tp1.inference

import com.example.tp1.extraction.MarkdownChunk
import com.example.tp1.extraction.chunkMarkdown
import java.nio.charset.CharacterCodingException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One loaded Jina owner. Requests are independent; concurrent requests and other loaded models are rejected. */
internal class EmbeddingSession private constructor(private val engine: LlamaEngine, val maxInputTokens: Int) {
    /** Adds Document: internally. Returns 512 finite, L2-normalized floats. Never truncates input. */
    suspend fun embedDocument(chunk: String): FloatArray = engine.embed(embeddingInput(chunk, query = false))

    /** Adds Query: internally, using the same retrieval weights as documents. */
    suspend fun embedQuery(query: String): FloatArray = engine.embed(embeddingInput(query, query = true))

    /** Includes Document: and EOS. Counts over-budget candidates without evaluating the model graph. */
    suspend fun countDocumentTokens(text: String): Int = engine.countEmbeddingTokens(embeddingInput(text, query = false))

    suspend fun chunkDocument(markdown: String): List<MarkdownChunk> = engine.withEmbeddingTokenCounter { count ->
        withContext(Dispatchers.Default) {
            chunkMarkdown(markdown, maxInputTokens) { count(embeddingInput(it, query = false)) }
        }
    }

    fun cancel() = engine.cancel()
    fun closeAsync(): Deferred<Unit> = engine.closeAsync()
    suspend fun close(): Unit = withContext(NonCancellable) { engine.closeAsync().await() }

    companion object {
        const val DIMENSIONS = 512

        /** Local verification/load only. Acquisition is explicit through ModelManager.download(). */
        suspend fun open(models: ModelManager, maxInputTokens: Int = 1024): EmbeddingSession =
            open(LlamaEngine(models), maxInputTokens)

        internal suspend fun open(engine: LlamaEngine, maxInputTokens: Int = 1024): EmbeddingSession {
            try {
                require(maxInputTokens in 1..8192) { "Embedding input limit must be between 1 and 8192 tokens" }
                engine.loadEmbedding(maxInputTokens)
                currentCoroutineContext().ensureActive()
                return EmbeddingSession(engine, maxInputTokens)
            } catch (error: Throwable) {
                try { withContext(NonCancellable) { engine.closeAsync().await() } }
                catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}

/** Shares the strict UTF-8/64 KiB JNI input contract with generation; role text counts toward that limit. */
internal fun embeddingInput(text: String, query: Boolean): ByteArray {
    require(text.isNotBlank()) { "Embedding text must not be blank" }
    require(text.length <= 64 * 1024) { "Embedding text exceeds byte limit" }
    return try {
        chatBytes((if (query) "Query: " else "Document: ") + text).also {
            require(it.size <= 64 * 1024) { "Embedding input exceeds 64 KiB, including prefix" }
        }
    } catch (error: CharacterCodingException) { throw IllegalArgumentException("Embedding text must be valid Unicode", error) }
}

package com.example.tp1.inference

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** Partial output retains its terminal status. Only EOG denotes natural completion. */
internal data class InferenceResponse(
    val text: String,
    val finishReason: FinishReason,
    val generatedTokens: Int,
    val incompleteUtf8Bytes: Int,
    val detail: String?
)

/**
 * One ready, model-bound native owner. Requests are independent, with no retained chat history.
 * Reuses loaded weights and rejects concurrent requests. While open, also excludes heavy extraction.
 * Always close in finally; close waits for native work even when the calling coroutine is cancelled.
 */
internal class InferenceSession private constructor(val model: Model, private val engine: LlamaEngine) {
    /** Uses a 4096-token context, at most 256 generated tokens and a 120-second cooperative deadline. */
    suspend fun infer(text: String): InferenceResponse = engine.generate("", text).toInferenceResponse()

    /** Stops the current request, retaining loaded weights for the next independent request. */
    fun cancel() = engine.cancel()

    /** Idempotent. Signals cancellation immediately and awaits the native resource-release barrier. */
    suspend fun close(): Unit = withContext(NonCancellable) { engine.closeAsync().await() }

    companion object {
        /** Verifies and loads a local model. Missing/invalid models fail; this never downloads. */
        suspend fun open(models: ModelManager, model: Model): InferenceSession = open(model, LlamaEngine(models))

        /** Concrete engine seam for device checks that pause/fail actual native work. Ownership transfers here. */
        internal suspend fun open(model: Model, engine: LlamaEngine): InferenceSession {
            try {
                engine.load(model)
                currentCoroutineContext().ensureActive()
                return InferenceSession(model, engine)
            } catch (error: Throwable) {
                try { withContext(NonCancellable) { engine.closeAsync().await() } }
                catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}

internal suspend fun Flow<GenerationEvent>.toInferenceResponse(): InferenceResponse {
    val text = StringBuilder()
    var terminal: GenerationEvent.Finished? = null
    collect { event ->
        check(terminal == null) { "Generation emitted after its terminal event" }
        when (event) {
            is GenerationEvent.Started -> Unit
            is GenerationEvent.Text -> text.append(event.value)
            is GenerationEvent.Finished -> terminal = event
        }
    }
    val end = checkNotNull(terminal) { "Generation ended without a terminal status" }
    return InferenceResponse(text.toString(), end.reason, end.generatedTokens, end.incompleteUtf8Bytes, end.detail)
}

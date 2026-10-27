package com.example.tp1.inference

import android.content.Context
import android.os.ParcelFileDescriptor
import com.example.tp1.extraction.ExtractionArtifacts
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resumeWithException

internal enum class FinishReason { EOG, LENGTH, CANCELLED, DEADLINE, NATIVE_FAILURE }
internal sealed interface GenerationEvent {
    data class Started(
        val inputTokens: Int, val bosCount: Int, val contextTokens: Int, val batchTokens: Int, val microBatchTokens: Int, val threads: Int
    ) : GenerationEvent
    data class Text(val value: String) : GenerationEvent
    data class Finished(
        val reason: FinishReason, val generatedTokens: Int, val incompleteUtf8Bytes: Int = 0, val detail: String? = null
    ) : GenerationEvent
}

/** Manually owned, never collection-owned. closeAsync().await() is the resource-release barrier. */
internal class LlamaEngine(private val models: ModelManager) {
    constructor(context: Context) : this(ModelManager(context))
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "TP1-inference") }
    private var handle = createNative()
    private var request: Any? = null
    private class Delivery { val stopped = AtomicBoolean(false) }
    private var delivery: Delivery? = null
    private var closing: CompletableDeferred<Unit>? = null
    private var model: ParcelFileDescriptor? = null // Worker-owned, released after native unload.
    private var memoryPermit = false

    // 0 idle, 1 native load, 2 prefill, 3 decode. Diagnostic only, not a synchronization primitive.
    val phase: Int get() = synchronized(lock) { if (handle == 0L) 0 else phaseNative(handle) }

    suspend fun load(selected: Model = Model.Test) {
        val coroutine = currentCoroutineContext()
        operation(onCancelled = ::releaseModel) { owner ->
            check(model == null) { "Model already loaded" }
            check(ExtractionArtifacts.permit.tryLock()) { "Extraction or another model owns the native memory permit" }
            memoryPermit = true
            try {
                val profile = models.profile(selected)
                val sampling = profile.sampling
                model = models.open(selected, coroutine) { cancelledNative(owner) }
                val fd = checkNotNull(model).fd
                if (!loadNative(owner, "/proc/self/fd/$fd".toByteArray(Charsets.UTF_8),
                        Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
                        sampling.temperature, sampling.topK, sampling.repetitionPenalty, sampling.repetitionWindow,
                        sampling.frequencyPenalty, sampling.presencePenalty, profile.requireSingleLeadingBos)) {
                    throw CancellationException("Model loading cancelled")
                }
            } catch (error: Throwable) {
                releaseModel(owner)
                throw error
            }
        }
    }

    /** No chat history is retained. Recollection is a new explicit request. Concurrent work is rejected. */
    fun generate(system: String, user: String, maxNewTokens: Int = 256, seed: Int = -1, deadlineMillis: Long = 120_000): Flow<GenerationEvent> =
        generate(system, listOf(ChatMessage(ChatRole.User, user)), maxNewTokens, seed, deadlineMillis)

    fun generate(system: String, messages: List<ChatMessage>, maxNewTokens: Int = 256, seed: Int = -1, deadlineMillis: Long = 120_000): Flow<GenerationEvent> = flow {
        val current = Delivery()
        synchronized(lock) {
            check(closing == null && request == null && delivery == null) { "Engine closed or busy" }
            delivery = current
        }
        try {
            stream(current, system, messages, maxNewTokens, seed, deadlineMillis).collect { event ->
                // Native work may finish before a slow collector. Stop also suppresses queued deltas.
                if (!current.stopped.get()) emit(event)
                else if (event is GenerationEvent.Finished) emit(event.copy(reason = FinishReason.CANCELLED))
            }
        } finally { synchronized(lock) { if (delivery === current) delivery = null } }
    }

    private fun stream(current: Delivery, system: String, messages: List<ChatMessage>, maxNewTokens: Int,
                       seed: Int, deadlineMillis: Long): Flow<GenerationEvent> = channelFlow {
        require(maxNewTokens in 1..512)
        require(deadlineMillis in 1..120_000)
        val (systemBytes, roles, contents) = encodeMessages(system, messages)
        operation(current) { owner ->
            check(model != null) { "No verified model loaded" }
            fun sendEvent(event: GenerationEvent) {
                if (!trySend(event).isSuccess) throw CancellationException("Generation collector stopped")
            }
            try {
                val info = beginNative(owner, systemBytes, roles, contents, maxNewTokens, seed, deadlineMillis)
                if (info[0] != 0) {
                    sendEvent(GenerationEvent.Finished(reason(info[0]), generatedNative(owner)))
                } else {
                    check(info.size == 7)
                    sendEvent(GenerationEvent.Started(info[1], info[2], info[3], info[4], info[5], info[6]))
                    val decoder = Utf8Stream()
                    while (true) {
                        val packet = stepNative(owner)
                        check(packet.isNotEmpty()) { "Empty native packet" }
                        val status = packet[0].toInt()
                        if (status == 0) {
                            val text = decoder.append(packet.copyOfRange(1, packet.size))
                            if (text.isNotEmpty()) sendEvent(GenerationEvent.Text(text))
                        } else {
                            val finish = reason(status)
                            // EOG is strict. Limits/cancellation report and discard a valid incomplete suffix.
                            val incomplete = decoder.pendingByteCount
                            if (finish == FinishReason.EOG || (finish == FinishReason.LENGTH && incomplete == 0)) {
                                val tail = decoder.append(byteArrayOf(), end = true)
                                if (tail.isNotEmpty()) sendEvent(GenerationEvent.Text(tail))
                            }
                            sendEvent(GenerationEvent.Finished(finish, generatedNative(owner), incomplete))
                            break
                        }
                    }
                }
            } catch (error: IOException) {
                sendEvent(GenerationEvent.Finished(FinishReason.NATIVE_FAILURE, generatedNative(owner), detail = error.message))
            } finally { resetNative(owner) }
        }
    }.buffer(Channel.UNLIMITED)

    suspend fun formatPrompt(system: String, user: String): String = formatPrompt(system, listOf(ChatMessage(ChatRole.User, user)))

    suspend fun formatPrompt(system: String, messages: List<ChatMessage>): String = operation { owner ->
        val (first, roles, contents) = encodeMessages(system, messages)
        String(formatNative(owner, first, roles, contents), Charsets.UTF_8)
    }

    suspend fun tokenCount(system: String, user: String): Int = tokenCount(system, listOf(ChatMessage(ChatRole.User, user)))

    suspend fun tokenCount(system: String, messages: List<ChatMessage>): Int = operation { owner ->
        val (first, roles, contents) = encodeMessages(system, messages)
        tokenCountNative(owner, first, roles, contents)
    }

    fun cancel() = synchronized(lock) {
        delivery?.stopped?.set(true)
        if (request != null && handle != 0L) cancelNative(handle)
    }

    /** Available only in debug and the explicit release-instrumentation native build. */
    fun testControl(pausePhase: Int = 0, forceLength: Boolean = false, failDecode: Boolean = false) = synchronized(lock) {
        check(closing == null && request == null && delivery == null)
        testControlNative(handle, pausePhase, forceLength, failDecode)
    }
    val testPaused: Boolean get() = synchronized(lock) { handle != 0L && testPausedNative(handle) }

    suspend fun reset() = operation { resetNative(it) }
    suspend fun unload() = operation { releaseModel(it) }

    /** Independent of caller scopes. Signals immediately, frees only after the queued worker has returned. */
    fun closeAsync(): Deferred<Unit> = synchronized(lock) {
        closing?.let { return@synchronized it }
        val done = CompletableDeferred<Unit>()
        closing = done
        delivery?.stopped?.set(true)
        cancelNative(handle)
        worker.execute {
            try {
                val owner = synchronized(lock) { handle }
                try { releaseModel(owner) } finally {
                    synchronized(lock) { destroyNative(handle); handle = 0 }
                }
                done.complete(Unit)
            } catch (error: Throwable) { done.completeExceptionally(error) }
            finally { worker.shutdown() }
        }
        done
    }

    private fun releaseModel(owner: Long) {
        try { unloadNative(owner) } finally {
            try { model?.close() } finally {
                model = null
                if (memoryPermit) { memoryPermit = false; ExtractionArtifacts.permit.unlock() }
            }
        }
    }

    private suspend fun <T> operation(current: Delivery? = null, onCancelled: ((Long) -> Unit)? = null, block: (Long) -> T): T {
        val finished = CompletableDeferred<Unit>()
        val ticket = Any()
        var succeeded = false
        var cancelled = false
        try {
            return suspendCancellableCoroutine { continuation ->
                try { synchronized(lock) {
                    if (closing != null || request != null || delivery !== current) {
                        continuation.resumeWithException(IllegalStateException("Engine closed or busy"))
                        finished.complete(Unit)
                    } else {
                        request = ticket
                        clearCancelNative(handle)
                        if (current?.stopped?.get() == true) cancelNative(handle)
                        continuation.invokeOnCancellation {
                            synchronized(lock) { if (request === ticket && handle != 0L) cancelNative(handle) }
                        }
                        worker.execute {
                            val result = runCatching { block(handle) }
                            succeeded = result.isSuccess
                            finished.complete(Unit)
                            continuation.resumeWith(result)
                        }
                    }
                } } catch (error: Throwable) {
                    synchronized(lock) { if (request === ticket) request = null }
                    finished.complete(Unit)
                    continuation.resumeWithException(error)
                }
            }
        } catch (error: CancellationException) {
            cancelled = true
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    // Keep the request reserved until delivery or cancellation cleanup has finished.
                    finished.await()
                    if (cancelled && succeeded && onCancelled != null) {
                        val released = synchronized(lock) {
                            // Close already owns cleanup if it was queued first.
                            closing ?: CompletableDeferred<Unit>().also { done ->
                                worker.execute {
                                    runCatching { onCancelled(handle) }.fold(done::complete, done::completeExceptionally)
                                }
                            }
                        }
                        released.await()
                    }
                } finally { synchronized(lock) { if (request === ticket) request = null } }
            }
        }
    }

    private fun encodeMessages(system: String, messages: List<ChatMessage>): Triple<ByteArray, IntArray, Array<ByteArray>> {
        require(messages.size in 1..1023 && messages.size % 2 == 1)
        require(messages.withIndex().all { (index, message) ->
            message.role == if (index % 2 == 0) ChatRole.User else ChatRole.Assistant
        }) { "Invalid chat role order" }
        val first = chatBytes(system)
        val contents = messages.map { chatBytes(it.text) }.toTypedArray()
        require(first.size.toLong() + contents.sumOf { it.size.toLong() } <= 64 * 1024) { "Request exceeds 64 KiB" }
        return Triple(first, messages.map { it.role.ordinal }.toIntArray(), contents)
    }

    private fun reason(status: Int) = when (status) {
        1 -> FinishReason.EOG
        2 -> FinishReason.LENGTH
        3 -> FinishReason.CANCELLED
        4 -> FinishReason.DEADLINE
        else -> error("Invalid native terminal reason: $status")
    }

    private external fun createNative(): Long
    private external fun destroyNative(owner: Long)
    private external fun loadNative(owner: Long, path: ByteArray, threads: Int,
                                    temperature: Float, topK: Int, repetitionPenalty: Float, repetitionWindow: Int,
                                    frequencyPenalty: Float, presencePenalty: Float, requireSingleLeadingBos: Boolean): Boolean
    private external fun unloadNative(owner: Long)
    private external fun formatNative(owner: Long, system: ByteArray, roles: IntArray, contents: Array<ByteArray>): ByteArray
    private external fun tokenCountNative(owner: Long, system: ByteArray, roles: IntArray, contents: Array<ByteArray>): Int
    private external fun beginNative(owner: Long, system: ByteArray, roles: IntArray, contents: Array<ByteArray>, maxTokens: Int, seed: Int, deadlineMillis: Long): IntArray
    private external fun stepNative(owner: Long): ByteArray
    private external fun resetNative(owner: Long)
    private external fun cancelNative(owner: Long)
    private external fun clearCancelNative(owner: Long)
    private external fun cancelledNative(owner: Long): Boolean
    private external fun phaseNative(owner: Long): Int
    private external fun generatedNative(owner: Long): Int
    private external fun testControlNative(owner: Long, pausePhase: Int, forceLength: Boolean, failDecode: Boolean)
    private external fun testPausedNative(owner: Long): Boolean

    private companion object { init { System.loadLibrary("tp1_llama") } }
}

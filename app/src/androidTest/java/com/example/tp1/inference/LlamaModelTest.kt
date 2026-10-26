package com.example.tp1.inference

import androidx.test.platform.app.InstrumentationRegistry
import android.os.SystemClock
import android.util.Log
import com.example.tp1.extraction.ExtractionArtifacts
import java.io.IOException
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/** Explicit model suite: missing model is a failure, never an assumption/skip. */
@ManualInference
class LlamaModelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun paused(engine: LlamaEngine) = withTimeout(60_000) {
        while (!engine.testPaused) delay(1)
    }

    private fun terminal(events: List<GenerationEvent>): GenerationEvent.Finished {
        assertEquals(1, events.count { it is GenerationEvent.Finished })
        return events.last() as GenerationEvent.Finished
    }

    @Test fun verifiedLoadTemplateBosShortResponseResetAndReload() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        try {
            engine.load()
            assertFalse("Loaded model must exclude parsing", ExtractionArtifacts.permit.tryLock())
            assertEquals("<|im_start|>system\nYou are helpful.<|im_end|>\n<|im_start|>user\nHi<|im_end|>\n<|im_start|>assistant\n",
                engine.formatPrompt("You are helpful.", "Hi"))
            repeat(2) {
                val started = SystemClock.elapsedRealtime()
                var firstTextMillis: Long? = null
                val events = engine.generate("You are helpful.", "Reply with the word hello.", 16, 42).onEach { event ->
                    if (event is GenerationEvent.Text && firstTextMillis == null) firstTextMillis = SystemClock.elapsedRealtime() - started
                }.toList()
                val info = events.first() as GenerationEvent.Started
                assertEquals(1, info.bosCount)
                assertTrue(info.inputTokens in 1..3584)
                assertTrue(info.contextTokens >= info.inputTokens + 16)
                assertTrue(info.batchTokens >= 128)
                assertTrue(info.microBatchTokens >= 128)
                assertTrue(info.threads in 1..4)
                val text = events.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.value }
                assertTrue("Expected actual generated text", text.isNotBlank())
                assertTrue(terminal(events).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))
                Log.i("TP1LlamaProof", "ctx=${info.contextTokens} batch=${info.batchTokens} threads=${info.threads} " +
                    "input=${info.inputTokens} generated=${terminal(events).generatedTokens} reason=${terminal(events).reason} " +
                    "firstTextMs=$firstTextMillis totalMs=${SystemClock.elapsedRealtime() - started} output=${text.take(256)}")
                engine.reset()
            }
            engine.unload()
            assertTrue(ExtractionArtifacts.permit.tryLock())
            ExtractionArtifacts.permit.unlock()
            engine.load()
            assertTrue(terminal(engine.generate("", "Say hello", 1, 42).toList()).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))
        } finally { engine.closeAsync().await() }
    }

    @Test fun requestAndTokenBoundariesAndDeadline() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        try {
            engine.load()
            val exactBytes = "é".repeat(32768)
            assertTrue(engine.formatPrompt("", exactBytes).contains(exactBytes))
            for ((user, cap) in listOf("hello" to 0, "hello" to 513, "x".repeat(65537) to 1,
                    "é".repeat(32769) to 1, "bad\u0000input" to 1)) {
                try { engine.generate("", user, cap).toList(); fail("Invalid request accepted") }
                catch (_: IllegalArgumentException) { }
            }
            try { engine.generate("", "\uD800", 1).toList(); fail("Unpaired surrogate accepted") }
            catch (_: java.nio.charset.CharacterCodingException) { }
            val base = engine.tokenCount("", "a")
            val boundary = "a" + " a".repeat(3584 - base)
            assertEquals(3584, engine.tokenCount("", boundary))
            assertEquals(3585, engine.tokenCount("", "$boundary a"))
            try { engine.generate("", "$boundary a", 1).toList(); fail("Oversized formatted prompt accepted") }
            catch (_: IllegalArgumentException) { }
            val events = engine.generate("", boundary, 512, 42, deadlineMillis = 1).toList()
            assertEquals(FinishReason.DEADLINE, terminal(events).reason)
            assertTrue(terminal(engine.generate("", "Hello", 1, 42).toList()).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))
            engine.testControl(forceLength = true)
            for (cap in listOf(1, 512)) {
                val deadline = 120_000L
                val started = SystemClock.elapsedRealtime()
                val end = terminal(engine.generate("", "Count from 1 upward, continuing the sequence.", cap, 42, deadline).toList())
                val elapsed = SystemClock.elapsedRealtime() - started
                val detail = "cap=$cap generated=${end.generatedTokens} reason=${end.reason} elapsedMs=$elapsed detail=${end.detail}"
                Log.i("TP1LlamaProof", detail)
                // A cap bounds output; the cooperative deadline may legitimately win on a slow target.
                if (end.reason == FinishReason.LENGTH) assertEquals(cap, end.generatedTokens)
                else if (end.reason == FinishReason.DEADLINE) assertTrue("Premature deadline: $detail", elapsed >= deadline)
                else fail(detail)
                assertTrue(detail, end.generatedTokens in 0..cap)
                assertTrue(end.incompleteUtf8Bytes in 0..3)
            }
        } finally { engine.closeAsync().await() }
    }

    @Test fun cancelDuringNativeLoadThenRetry() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        try {
            engine.testControl(pausePhase = 1)
            val pending = async(Dispatchers.IO) { engine.load() }
            try {
                paused(engine)
                assertEquals(1, engine.phase)
                repeat(3) { engine.cancel() }
                try { pending.await(); fail("Cancelled load succeeded") } catch (_: CancellationException) { }
            } finally { pending.cancelAndJoin() }
            engine.load()
            engine.unload()
        } finally { engine.closeAsync().await() }
    }

    @Test fun cancelAfterNativeLoadBeforeCallerResumesReleasesModel() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        val queued = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.complete(Unit)
                Dispatchers.IO.dispatch(context, Runnable {
                    resume.await()
                    block.run()
                })
            }
        }
        // Start on this thread, then hold only the return from the blocking native load.
        val pending = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { engine.load() }
        try {
            withTimeout(60_000) { queued.await() }
            assertFalse(pending.isCompleted)
            val availableBeforeCancel = ExtractionArtifacts.permit.tryLock()
            if (availableBeforeCancel) ExtractionArtifacts.permit.unlock()
            assertFalse("Native load must succeed before cancelling delivery", availableBeforeCancel)
            pending.cancel()
            resume.countDown()
            withTimeout(60_000) { pending.join() }
            assertTrue(pending.isCancelled)
            val released = ExtractionArtifacts.permit.tryLock()
            if (released) ExtractionArtifacts.permit.unlock()
            assertTrue("Cancelled load must release its permit before returning", released)
            engine.load()
            assertTrue(terminal(engine.generate("", "Hello", 1, 42).toList()).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))
            engine.unload()
        } finally {
            resume.countDown()
            pending.cancelAndJoin()
            engine.closeAsync().await()
        }
    }

    @Test fun cancelPrefillAndCollectorThenCloseDuringGeneration() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        try {
            engine.load()
            val longPrompt = "a" + " a".repeat(2000)
            engine.testControl(pausePhase = 2)
            val prefill = async(Dispatchers.IO) { engine.generate("", longPrompt, 512, 42).toList() }
            try {
                paused(engine)
                assertEquals(2, engine.phase)
                repeat(3) { engine.cancel() }
                assertEquals(FinishReason.CANCELLED, terminal(prefill.await()).reason)
            } finally { prefill.cancelAndJoin() }
            engine.testControl(pausePhase = 2)
            val collector = async(Dispatchers.IO) { engine.generate("", longPrompt, 512, 42).collect() }
            try {
                paused(engine)
                try { engine.reset(); fail("Concurrent reset accepted") } catch (_: IllegalStateException) { }
            } finally { collector.cancelAndJoin() }
            assertEquals(0, engine.phase)
            engine.testControl(pausePhase = 3, forceLength = true)
            val decoding = async(Dispatchers.IO) { engine.generate("", "Count from 1 to 1000.", 512, 42).toList() }
            try {
                paused(engine)
                assertEquals(3, engine.phase)
                engine.closeAsync().await()
                assertEquals(FinishReason.CANCELLED, terminal(decoding.await()).reason)
            } finally { decoding.cancelAndJoin() }
        } finally { engine.closeAsync().await() }
    }

    @Test fun nativeDecodeFailureIsExplicitAndNextRequestRecovers() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        try {
            engine.load()
            engine.testControl(failDecode = true)
            val failure = terminal(engine.generate("", "Hello", 8, 42).toList())
            assertEquals(FinishReason.NATIVE_FAILURE, failure.reason)
            assertTrue(failure.detail!!.contains("llama_decode failed"))
            assertEquals(0, engine.phase)
            assertTrue(terminal(engine.generate("", "Hello", 1, 42).toList()).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))
        } finally { engine.closeAsync().await() }
    }

    @Test fun stopDropsQueuedTextEvenAfterNativeGenerationFinished() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        try {
            engine.load()
            engine.testControl(forceLength = true)
            val events = mutableListOf<GenerationEvent>()
            engine.generate("", "Count upward", 8, 42).collect { event ->
                events += event
                if (event is GenerationEvent.Started) {
                    // Hold delivery while the bounded producer finishes and queues its output.
                    withTimeout(120_000.milliseconds) { while (engine.phase != 0) delay(1) }
                    engine.cancel()
                }
            }
            assertFalse(events.any { it is GenerationEvent.Text })
            assertEquals(FinishReason.CANCELLED, terminal(events).reason)
            assertEquals(8, terminal(events).generatedTokens)
        } finally { engine.closeAsync().await() }
    }

    @Test fun failedMissingModelLoadCanRetryAfterRestoration() = runBlocking<Unit> {
        val file = ModelFile.location(context)
        check(file.isFile) { "Provision the pinned model first" }
        val temporarilyMoved = java.io.File(file.parentFile, "retry-model.part")
        check(!temporarilyMoved.exists())
        val engine = LlamaEngine(context)
        try {
            check(file.renameTo(temporarilyMoved))
            try { engine.load(); fail("Missing model accepted") } catch (_: IOException) { }
            finally { check(temporarilyMoved.renameTo(file)) }
            engine.load()
            assertTrue(terminal(engine.generate("", "Hi", 1, 42).toList()).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))
        } finally {
            engine.closeAsync().await()
            if (temporarilyMoved.exists()) check(temporarilyMoved.renameTo(file))
        }
    }
}

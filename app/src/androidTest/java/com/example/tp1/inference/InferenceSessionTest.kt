package com.example.tp1.inference

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.extraction.ExtractionArtifacts
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@ManualInference
class InferenceSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ModelManager(context)

    private suspend fun paused(engine: LlamaEngine) = withTimeout(60_000) {
        while (!engine.testPaused) delay(1)
    }

    private fun assertReleased() {
        val released = ExtractionArtifacts.permit.tryLock()
        if (released) ExtractionArtifacts.permit.unlock()
        assertTrue("Native memory permit leaked", released)
    }

    @Test fun openIsReadyReusesModelAndRejectsSecondLoadedSession() = runBlocking<Unit> {
        val offline = ModelManager(java.io.File(context.noBackupFilesDir, "models"),
            connect = { error("Cached use must stay offline") })
        val session = InferenceSession.open(offline, Model.Test)
        try {
            assertEquals(Model.Test, session.model)
            offline.download(Model.Test)
            try { InferenceSession.open(offline, Model.Test); fail("Second loaded owner accepted") }
            catch (_: IllegalStateException) { }
            repeat(2) {
                val response = session.infer("Reply with the word hello.")
                assertTrue(response.text.isNotBlank())
                assertTrue(response.finishReason in setOf(FinishReason.EOG, FinishReason.LENGTH))
                assertTrue(response.generatedTokens in 1..256)
                assertTrue(response.incompleteUtf8Bytes in 0..3)
            }
            assertFalse(ExtractionArtifacts.permit.tryLock())
        } finally { session.close() }
        assertReleased()
        session.close()
        try { session.infer("Hello"); fail("Closed session accepted work") } catch (_: IllegalStateException) { }
    }

    @Test fun stopRejectsConcurrentWorkAndNextIndependentRequestRecovers() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = InferenceSession.open(Model.Test, engine)
        try {
            engine.testControl(pausePhase = 2)
            val pending = async(Dispatchers.IO) { session.infer("Say hello.") }
            try {
                paused(engine)
                try { session.infer("Concurrent prompt"); fail("Concurrent inference accepted") }
                catch (_: IllegalStateException) { }
                repeat(3) { session.cancel() }
                assertEquals(FinishReason.CANCELLED, pending.await().finishReason)
            } finally { pending.cancelAndJoin() }
            assertTrue(session.infer("Say hello.").text.isNotBlank())
        } finally { session.close() }
        assertReleased()
    }

    @Test fun coroutineCancellationWaitsForNativeAndRetainsLoadedModel() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = InferenceSession.open(Model.Test, engine)
        try {
            engine.testControl(pausePhase = 3, forceLength = true)
            val pending = async(Dispatchers.IO) { session.infer("Count upward from 1.") }
            try { paused(engine) } finally { withTimeout(60_000) { pending.cancelAndJoin() } }
            assertEquals(0, engine.phase)
            assertTrue(pending.isCancelled)
            assertFalse(ExtractionArtifacts.permit.tryLock())
            engine.testControl()
            assertTrue(session.infer("Say hello.").text.isNotBlank())
        } finally { session.close() }
        assertReleased()
    }

    @Test fun closeFromCancelledOwnerStopsActiveWorkAndAwaitsRelease() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = InferenceSession.open(Model.Test, engine)
        try {
            engine.testControl(pausePhase = 3, forceLength = true)
            val pending = async(Dispatchers.IO) { session.infer("Count upward from 1.") }
            try {
                paused(engine)
                val owner = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { session.close() }
                }
                withTimeout(60_000) { owner.cancelAndJoin() }
                assertReleased()
                assertEquals(FinishReason.CANCELLED, pending.await().finishReason)
                session.close()
            } finally { pending.cancelAndJoin() }
        } finally { session.close() }
    }

    @Test fun nativeFailureAndLengthRetainTerminalDetails() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = InferenceSession.open(Model.Test, engine)
        try {
            val prepared = engine.generate("", "Hello", maxNewTokens = 1).toList()
            assertEquals(4096, (prepared.first() as GenerationEvent.Started).contextTokens)
            engine.testControl(failDecode = true)
            val failure = session.infer("Say hello.")
            assertEquals(FinishReason.NATIVE_FAILURE, failure.finishReason)
            assertTrue(failure.detail!!.contains("llama_decode failed"))
            engine.testControl(forceLength = true)
            val started = SystemClock.elapsedRealtime()
            val response = session.infer("Count upward from 1, continuing the sequence.")
            if (response.finishReason == FinishReason.LENGTH) assertEquals(256, response.generatedTokens)
            else {
                assertEquals(FinishReason.DEADLINE, response.finishReason)
                assertTrue("Deadline must not finish early", SystemClock.elapsedRealtime() - started >= 120_000)
            }
            assertTrue(response.generatedTokens in 0..256)
        } finally { session.close() }
    }

    @Test fun cancelledOpenBeforeLoadDeliveryReleasesNativeOwner() = runBlocking<Unit> {
        val queued = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.complete(Unit)
                Dispatchers.IO.dispatch(context, Runnable {
                    check(resume.await(60, TimeUnit.SECONDS))
                    block.run()
                })
            }
        }
        val pending = async(dispatcher, start = CoroutineStart.UNDISPATCHED) { InferenceSession.open(models, Model.Test) }
        try {
            withTimeout(60_000) { queued.await() }
            val available = ExtractionArtifacts.permit.tryLock()
            if (available) ExtractionArtifacts.permit.unlock()
            assertFalse("Load must succeed before cancelling return delivery", available)
            pending.cancel()
            resume.countDown()
            withTimeout(60_000) { pending.join() }
            assertTrue(pending.isCancelled)
            assertReleased()
            InferenceSession.open(models, Model.Test).close()
        } finally {
            resume.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun selectedProfileControlsNativeBosPolicyAndGreedySampling() = runBlocking<Unit> {
        // The fixture's tokenizer recognizes this as its BOS, in addition to the automatic leading BOS.
        val prompt = "<|startoftext|> Say hello."
        val strict = LlamaEngine(models)
        try {
            strict.load()
            val end = strict.generate("", prompt, 1, 42).toList().last() as GenerationEvent.Finished
            assertEquals(FinishReason.NATIVE_FAILURE, end.reason)
            assertTrue(end.detail!!.contains("requires exactly one leading BOS"))
        } finally { strict.closeAsync().await() }

        val defaults = Model.Test.profile.sampling
        // Exercise temperature and top-k independently instead of enabling both greedy controls together.
        for (sampling in listOf(defaults.copy(temperature = 0f), defaults.copy(topK = 1))) {
            val profile = Model.Test.profile.copy(sampling = sampling, requireSingleLeadingBos = false)
            val configured = ModelManager(models.location(Model.Test).parentFile!!, { profile },
                connect = { error("Profile selection must not download") })
            val engine = LlamaEngine(configured)
            val session = InferenceSession.open(Model.Test, engine)
            try {
                val events = engine.generate("", prompt, 1, 42).toList()
                assertEquals(2, (events.first() as GenerationEvent.Started).bosCount)
                assertTrue((events.last() as GenerationEvent.Finished).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))
                engine.testControl(forceLength = true)
                val outputs = listOf(42, 43).map { seed ->
                    val generated = engine.generate("", "Count upward from 1.", 8, seed).toList()
                    assertEquals(FinishReason.LENGTH, (generated.last() as GenerationEvent.Finished).reason)
                    generated.filterIsInstance<GenerationEvent.Text>().joinToString("") { it.value }
                }
                assertTrue(outputs.first().isNotEmpty())
                assertEquals("Greedy sampling must not depend on the random seed", outputs.first(), outputs.last())
            } finally { session.close() }
        }
        assertReleased()
    }

    @Test fun systemAndHistoryUseRealRolesAndStreamWithinTheBudget() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val system = "You are helpful. Reply briefly."
        val session = InferenceSession.open(Model.Test, engine, system)
        try {
            val history = listOf(ChatMessage(ChatRole.User, "My name is Ana."), ChatMessage(ChatRole.Assistant, "Hello Ana."))
            val messages = session.prepare("Say hello.", history)
            assertEquals(history + ChatMessage(ChatRole.User, "Say hello."), messages)
            assertEquals("<|im_start|>system\n$system<|im_end|>\n" +
                "<|im_start|>user\nMy name is Ana.<|im_end|>\n" +
                "<|im_start|>assistant\nHello Ana.<|im_end|>\n" +
                "<|im_start|>user\nSay hello.<|im_end|>\n<|im_start|>assistant\n", engine.formatPrompt(system, messages))
            val count = engine.tokenCount(system, messages)
            val events = session.stream(messages).toList()
            val info = events.first() as GenerationEvent.Started
            assertEquals(count, info.inputTokens)
            assertEquals(1, info.bosCount)
            assertEquals(4096, info.contextTokens)
            assertTrue(info.inputTokens + 256 < info.contextTokens)
            assertTrue(info.inputTokens <= 3584)
            assertTrue(events.any { it is GenerationEvent.Text && it.value.isNotEmpty() })
            assertTrue(events.last() is GenerationEvent.Finished)
            assertTrue((events.last() as GenerationEvent.Finished).reason in setOf(FinishReason.EOG, FinishReason.LENGTH))

            val largeHistory = listOf(ChatMessage(ChatRole.User, "a" + " a".repeat(3600)),
                ChatMessage(ChatRole.Assistant, "Old answer")) + history
            assertEquals(messages, session.prepare("Say hello.", largeHistory))
            try { session.prepare("a" + " a".repeat(3600)); fail("Oversized latest input accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { session.closeAsync().await() }
        assertReleased()
    }
}

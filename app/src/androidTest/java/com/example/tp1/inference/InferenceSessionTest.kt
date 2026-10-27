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
                assertTrue(response.generatedTokens in 1..32)
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
            engine.testControl(failDecode = true)
            val failure = session.infer("Say hello.")
            assertEquals(FinishReason.NATIVE_FAILURE, failure.finishReason)
            assertTrue(failure.detail!!.contains("llama_decode failed"))
            engine.testControl(forceLength = true)
            val started = SystemClock.elapsedRealtime()
            val response = session.infer("Count upward from 1, continuing the sequence.")
            if (response.finishReason == FinishReason.LENGTH) assertEquals(32, response.generatedTokens)
            else {
                assertEquals(FinishReason.DEADLINE, response.finishReason)
                assertTrue("Deadline must not finish early", SystemClock.elapsedRealtime() - started >= 120_000)
            }
            assertTrue(response.generatedTokens in 0..32)
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
}

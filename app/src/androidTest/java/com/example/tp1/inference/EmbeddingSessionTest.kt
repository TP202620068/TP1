package com.example.tp1.inference

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.extraction.ExtractionArtifacts
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Explicitly selected real-GGUF checks. Provision JinaV5NanoRetrieval before running. */
@ManualInference
class EmbeddingSessionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = ModelManager(context)

    private fun assertVector(vector: FloatArray) {
        assertEquals(512, vector.size)
        assertTrue(vector.all { it.isFinite() })
        assertEquals(1.0, vector.sumOf { it.toDouble() * it }, 1e-5)
    }

    private fun dot(first: FloatArray, second: FloatArray): Double =
        first.indices.sumOf { first[it].toDouble() * second[it] }

    private fun assertReleased() {
        val acquired = ExtractionArtifacts.permit.tryLock()
        if (acquired) ExtractionArtifacts.permit.unlock()
        assertTrue("Native memory permit leaked", acquired)
    }

    private suspend fun paused(engine: LlamaEngine) = withTimeout(60_000) {
        while (!engine.testPaused) delay(1)
    }

    @Test fun rolePrefixesTokenizerAndMultilingualRetrievalUseRealWeights() = runBlocking<Unit> {
        val offline = ModelManager(models.location(Model.JinaV5NanoRetrieval).parentFile!!,
            connect = { error("Embedding must stay offline after provisioning") })
        offline.download(Model.JinaV5NanoRetrieval)
        val engine = LlamaEngine(offline)
        val session = EmbeddingSession.open(engine)
        try {
            assertEquals(1024, session.maxInputTokens)
            val cases = listOf(
                Triple("How long is the laptop warranty?", "The laptop warranty lasts two years.", "Tomatoes grow best in sunny gardens."),
                Triple("¿Cuánto dura la garantía de la laptop?", "La garantía de la laptop dura dos años.", "Los tomates crecen en jardines soleados.")
            )
            for ((query, relevant, unrelated) in cases) {
                val queryVector = session.embedQuery(query)
                val documentVector = session.embedDocument(relevant)
                val unrelatedVector = session.embedDocument(unrelated)
                listOf(queryVector, documentVector, unrelatedVector).forEach(::assertVector)
                assertTrue("Relevant document must outrank unrelated text", dot(queryVector, documentVector) > dot(queryVector, unrelatedVector))
                assertArrayEquals(documentVector, engine.embed("Document: $relevant".toByteArray()), 1e-6f)
                assertArrayEquals(queryVector, engine.embed("Query: $query".toByteArray()), 1e-6f)
                assertFalse(documentVector.contentEquals(session.embedQuery(relevant)))
            }
            // Regenerate with llama.cpp 280141e6d4769b1e4424de999437cc72ffac33d6 and the GGUF in JinaV5NanoRetrieval.
            // Build Release with GGML_NATIVE=OFF, GGML_CPU_ARM_ARCH=armv8-a and
            // GGML_METAL=GGML_ACCELERATE=GGML_BLAS=GGML_CPU_KLEIDIAI=OFF to match Android's CPU baseline.
            // llama-embedding -m v5-nano-retrieval-IQ4_NL.gguf --pooling last -c 1024 -b 1024 -ub 1024 -t 4 \
            //   --embd-normalize -1 --embd-output-format array -p 'Document: The laptop warranty lasts two years.'
            // Parse the JSON array, retain its first 512 coordinates, then L2-normalize those coordinates.
            // Base64-encode struct.pack("<512f", *normalized) into the fixture below. Raw retained norm: 88.66635675523608.
            val encoded = InstrumentationRegistry.getInstrumentation().context.assets
                .open("inference/jina-v5-nano-iq4-nl-512.b64").bufferedReader().use { it.readText() }
            val buffer = ByteBuffer.wrap(Base64.decode(encoded, Base64.DEFAULT)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val reference = FloatArray(512).also { buffer.get(it) }
            val actual = session.embedDocument("The laptop warranty lasts two years.")
            val similarity = dot(reference, actual)
            assertTrue("Android/upstream similarity $similarity; first values ${actual.take(8)}", similarity > 0.99999)

            val fullInput = "a" + " a".repeat(1020)
            assertEquals(1024, engine.testEmbeddingTokens(embeddingInput(fullInput, query = false)).size)
            assertVector(session.embedDocument(fullInput))
            val input = embeddingInput("Unicode 😀 <|begin_of_text|> <|end_of_text|>", query = false)
            val tokens = engine.testEmbeddingTokens(input)
            assertEquals(128001, tokens.last())
            assertEquals(1, tokens.count { it == 128001 })
            assertFalse(tokens.contains(128000))
            assertVector(session.embedDocument("Unicode 😀 <|begin_of_text|> <|end_of_text|>"))
            try { engine.formatPrompt("", "Chat request"); fail("Embedding model accepted chat") }
            catch (_: IllegalArgumentException) { }
        } finally { session.close() }
        assertReleased()
    }

    @Test fun exactInputLimitIncludesPrefixAndEosAndRejectionsDoNotPoisonNextRequest() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = EmbeddingSession.open(engine, maxInputTokens = 32)
        try {
            for (query in listOf(false, true)) {
                var text = "a"
                while (engine.testEmbeddingTokens(embeddingInput(text, query)).size < 32) text += " a"
                assertEquals(32, engine.testEmbeddingTokens(embeddingInput(text, query)).size)
                val atLimit = if (query) session.embedQuery(text) else session.embedDocument(text)
                assertVector(atLimit)
                try {
                    if (query) session.embedQuery("$text a") else session.embedDocument("$text a")
                    fail("Input over token limit accepted")
                } catch (_: IllegalArgumentException) { }
            }
            for (text in listOf("", " \n", "bad\u0000text", "bad\uD800", "x".repeat(65536))) {
                try { session.embedDocument(text); fail("Invalid input accepted") } catch (_: IllegalArgumentException) { }
            }
            try { engine.embed("unprefixed text".toByteArray()); fail("Missing role accepted") }
            catch (_: IllegalArgumentException) { }
            assertVector(session.embedQuery("garantía"))
        } finally { session.close() }
        assertReleased()
    }

    @Test fun tokenCountingMatchesEncodingButAcceptsOversizedCandidatesWithoutEncoding() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = EmbeddingSession.open(engine, maxInputTokens = 32)
        try {
            val text = "a" + " a".repeat(28)
            assertEquals(32, session.countDocumentTokens(text))
            assertEquals(engine.testEmbeddingTokens(embeddingInput(text, query = false)).size, session.countDocumentTokens(text))
            assertEquals(33, session.countDocumentTokens("$text a"))
            try { session.embedDocument("$text a"); fail("Oversized encoding accepted") }
            catch (_: IllegalArgumentException) { }
            for (invalid in listOf("", " \n", "bad\u0000text", "bad\uD800", "x".repeat(65536))) {
                try { session.countDocumentTokens(invalid); fail("Invalid count input accepted") }
                catch (_: IllegalArgumentException) { }
            }
            val literal = "Unicode 😀 <|begin_of_text|> <|end_of_text|>"
            assertEquals(engine.testEmbeddingTokens(embeddingInput(literal, query = false)).size, session.countDocumentTokens(literal))
            engine.testControl(failDecode = true)
            assertEquals(32, session.countDocumentTokens(text))
            assertEquals(0, engine.phase)
            try { session.embedDocument(text); fail("Counting consumed the test's encoding failure") }
            catch (_: IOException) { }
            assertVector(session.embedDocument(text))
        } finally { session.close() }
        try { session.countDocumentTokens("Closed"); fail("Closed session accepted counting") }
        catch (_: IllegalStateException) { }
        assertReleased()
    }

    @Test fun compoundCountingRejectsConcurrentCallsAndStopsBetweenNativeRequests() = runBlocking<Unit> {
        for (afterCount in listOf(false, true)) for (stop in listOf("cancel", "caller", "close")) {
            val engine = LlamaEngine(models)
            val session = EmbeddingSession.open(engine)
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            val input = embeddingInput("A real token count.", query = false)
            val pending = async(Dispatchers.IO) {
                try {
                    engine.withEmbeddingTokenCounter { count ->
                        if (afterCount) assertTrue(count(input) > 0)
                        entered.complete(Unit)
                        resume.await()
                        count(input)
                    }
                    fail("Stopped compound request returned")
                } catch (_: CancellationException) {
                    if (stop != "caller") currentCoroutineContext().ensureActive()
                }
            }
            try {
                withTimeout(60_000) { entered.await() }
                assertEquals(0, engine.phase)
                try { session.embedQuery("concurrent"); fail("Concurrent embedding accepted") }
                catch (_: IllegalStateException) { }
                try { session.countDocumentTokens("concurrent"); fail("Concurrent counting accepted") }
                catch (_: IllegalStateException) { }
                try { session.chunkDocument(""); fail("Concurrent blank chunking accepted") }
                catch (_: IllegalStateException) { }
                when (stop) {
                    "cancel" -> session.cancel()
                    "caller" -> pending.cancel()
                    "close" -> {
                        val closed = session.closeAsync()
                        assertSame(closed, session.closeAsync())
                        withTimeout(60_000) { closed.await() }
                    }
                }
                withTimeout(60_000) { pending.join() }
                assertEquals("Session stop cancelled its caller", stop == "caller", pending.isCancelled)
                if (stop != "close") {
                    val failure = IOException("Preprocessing failed")
                    try { engine.withEmbeddingTokenCounter<Unit> { throw failure }; fail("Failure swallowed") }
                    catch (actual: IOException) { assertSame(failure, actual) }
                    assertEquals(1, session.chunkDocument("A healthy chunk after cancellation.").size)
                    assertVector(session.embedQuery("Recovery without reloading weights."))
                } else {
                    try { session.chunkDocument(""); fail("Closed session accepted blank chunking") }
                    catch (_: IllegalStateException) { }
                }
            } finally {
                resume.complete(Unit)
                pending.cancelAndJoin()
                session.close()
            }
            assertReleased()
        }
    }

    @Test fun cancellationAndNativeFailureRetainWeightsAndRejectConcurrentRequests() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = EmbeddingSession.open(engine)
        try {
            engine.testControl(pausePhase = 2)
            val pending = async(Dispatchers.IO) { session.embedDocument("A synthetic document.") }
            try {
                paused(engine)
                try { session.embedQuery("concurrent"); fail("Concurrent request accepted") }
                catch (_: IllegalStateException) { }
                try { session.countDocumentTokens("concurrent"); fail("Concurrent counting accepted") }
                catch (_: IllegalStateException) { }
                session.cancel()
                try { pending.await(); fail("Cancelled embedding returned a vector") } catch (_: CancellationException) { }
            } finally { pending.cancelAndJoin() }
            assertEquals(0, engine.phase)
            assertFalse(ExtractionArtifacts.permit.tryLock())
            engine.testControl(failDecode = true)
            try { session.embedDocument("Fail this request."); fail("Native failure ignored") }
            catch (error: IOException) { assertTrue(error.message!!.contains("llama_encode")) }
            assertVector(session.embedDocument("A healthy independent request."))

            engine.testControl(pausePhase = 2)
            val cancelled = async(Dispatchers.IO) { session.embedQuery("Cancel the caller.") }
            try { paused(engine) } finally { withTimeout(60_000) { cancelled.cancelAndJoin() } }
            assertEquals(0, engine.phase)
            assertVector(session.embedQuery("Another healthy request."))
        } finally { session.close() }
        assertReleased()
    }

    @Test fun closeStopsWorkAndSessionExcludesOtherLoadedModels() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        val session = EmbeddingSession.open(engine)
        try {
            try { EmbeddingSession.open(models); fail("Second loaded owner accepted") }
            catch (_: IllegalStateException) { }
            try { InferenceSession.open(models, Model.Test); fail("Chat loaded alongside embeddings") }
            catch (_: IllegalStateException) { }
            assertFalse(ExtractionArtifacts.permit.tryLock())
            engine.testControl(pausePhase = 2)
            val pending = async(Dispatchers.IO) { session.embedQuery("Close while processing.") }
            try {
                paused(engine)
                val closed = session.closeAsync()
                assertSame(closed, session.closeAsync())
                withTimeout(60_000) { closed.await() }
                try { pending.await(); fail("Closed owner returned a vector") } catch (_: CancellationException) { }
            } finally { pending.cancelAndJoin() }
        } finally { session.close() }
        assertReleased()
        try { session.embedDocument("Closed"); fail("Closed owner accepted work") } catch (_: IllegalStateException) { }

        val chat = LlamaEngine(models)
        try {
            chat.load()
            try { chat.embed(embeddingInput("Wrong model", query = false)); fail("Chat model accepted embedding") }
            catch (_: IllegalArgumentException) { }
            val events = chat.generate("", "Say hello.", maxNewTokens = 1).toList()
            assertTrue(events.last() is GenerationEvent.Finished)
            assertFalse((events.last() as GenerationEvent.Finished).reason == FinishReason.NATIVE_FAILURE)
        } finally { chat.closeAsync().await() }
        assertReleased()
    }

    @Test fun cancelledOpenWaitsForLoadAndReleasesOwner() = runBlocking<Unit> {
        val engine = LlamaEngine(models)
        engine.testControl(pausePhase = 1)
        val pending = async(Dispatchers.IO) { EmbeddingSession.open(engine) }
        try { paused(engine) } finally { withTimeout(60_000) { pending.cancelAndJoin() } }
        engine.closeAsync().await()
        assertReleased()
        val next = EmbeddingSession.open(models)
        try { assertVector(next.embedDocument("Reload after cancelled loading.")) } finally { next.close() }
    }

    @Test fun cancelAndCloseSuppressResultsQueuedAfterCompletion() = runBlocking<Unit> {
        for (kind in listOf("embedding", "counting", "chunking")) for (close in listOf(false, true)) {
            val session = EmbeddingSession.open(models)
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
            val pending = async<Any>(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                when (kind) {
                    "counting" -> session.countDocumentTokens("A completed count must not escape cancellation.")
                    "chunking" -> session.chunkDocument("A completed chunk list must not escape cancellation.")
                    else -> session.embedDocument("A completed vector must not escape cancellation.")
                }
            }
            try {
                withTimeout(60_000) { queued.await() }
                assertFalse(pending.isCompleted)
                try { session.chunkDocument(""); fail("Concurrent chunking accepted before result delivery") }
                catch (_: IllegalStateException) { }
                if (close) session.close() else session.cancel()
                resume.countDown()
                try { pending.await(); fail("Stopped owner delivered a queued result") }
                catch (_: CancellationException) { }
                if (!close) assertVector(session.embedQuery("Recovery after cancelled delivery."))
            } finally {
                resume.countDown()
                pending.cancelAndJoin()
                session.close()
            }
            assertReleased()
        }
    }
}

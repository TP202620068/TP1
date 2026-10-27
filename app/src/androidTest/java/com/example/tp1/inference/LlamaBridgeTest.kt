package com.example.tp1.inference

import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.security.MessageDigest
import com.example.tp1.extraction.ExtractionArtifacts
import org.junit.Assert.*
import org.junit.Test

/** No model required. Native registration and malformed-artifact rejection are real assertions. */
class LlamaBridgeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun registrationAndIdempotentClose() = runBlocking<Unit> {
        val engine = LlamaEngine(context)
        assertEquals(0, engine.phase)
        engine.reset()
        engine.unload()
        val closed = engine.closeAsync()
        assertSame(closed, engine.closeAsync())
        closed.await()
        try { engine.reset(); fail("Closed engine accepted work") } catch (_: IllegalStateException) { }
    }

    @Test fun malformedProvisioningIsRejectedAndStagingIsRemoved() {
        val root = File(context.cacheDir, "llama-invalid-${System.nanoTime()}")
        val models = ModelManager(File(root, "models"))
        try {
            assertThrows(IOException::class.java) {
                ModelFile.provision(models.location(Model.Test), Model.Test.artifact, ByteArrayInputStream("GGUFbad".toByteArray())) { }
            }
            assertFalse(models.location(Model.Test).exists())
            assertTrue(File(root, "models").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun sessionRequiresLocalModelAndReleasesFailedOwner() = runBlocking<Unit> {
        val root = File(context.cacheDir, "session-missing-${System.nanoTime()}")
        try {
            val models = ModelManager(root, connect = { error("Opening a session must never download") })
            try { InferenceSession.open(models, Model.Test); fail("Missing model accepted") } catch (_: IOException) { }
            assertFalse(models.isAvailable(Model.Test))
            val released = ExtractionArtifacts.permit.tryLock()
            if (released) ExtractionArtifacts.permit.unlock()
            assertTrue("Failed open leaked the native memory permit", released)
        } finally { root.deleteRecursively() }
    }

    @Test fun smallDownloadExercisesAndroidPublicationAndCacheReuse() = runBlocking<Unit> {
        val root = File(context.cacheDir, "model-fixture-${System.nanoTime()}")
        val bytes = "verified Android fixture".toByteArray()
        val artifact = ModelArtifact("fixture.gguf", "https://example.invalid/model", bytes.size.toLong(),
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })
        var connections = 0
        val models = ModelManager(root, { artifact }, { url ->
            connections++
            object : HttpURLConnection(url) {
                override fun connect() { }
                override fun disconnect() { }
                override fun usingProxy() = false
                override fun getResponseCode() = HTTP_OK
                override fun getInputStream() = ByteArrayInputStream(bytes)
            }
        })
        try {
            models.download(Model.Test)
            models.download(Model.Test)
            assertTrue(models.isAvailable(Model.Test))
            assertArrayEquals(bytes, models.location(Model.Test).readBytes())
            assertEquals(1, connections)
            assertEquals(setOf(artifact.name, ".publish.lock"), root.list()!!.toSet())
        } finally { root.deleteRecursively() }
    }
}

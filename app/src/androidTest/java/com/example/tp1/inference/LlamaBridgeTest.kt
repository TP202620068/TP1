package com.example.tp1.inference

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
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
        val isolated = object : ContextWrapper(context) { override fun getNoBackupFilesDir(): File = root }
        try {
            assertThrows(IOException::class.java) { ModelFile.provision(isolated, ByteArrayInputStream("GGUFbad".toByteArray())) }
            assertFalse(ModelFile.location(isolated).exists())
            assertTrue(File(root, "models").listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }
}

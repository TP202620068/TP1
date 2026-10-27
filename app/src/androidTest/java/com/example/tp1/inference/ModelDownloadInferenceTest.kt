package com.example.tp1.inference

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit network proof. Requires -e tp1.downloadModel true and downloads about 153 MB once. */
@ManualInference
class ModelDownloadInferenceTest {
    @Test fun downloadThenReuseAndInferOffline() = runBlocking<Unit> {
        check(InstrumentationRegistry.getArguments().getString("tp1.downloadModel") == "true") {
            "Opt in explicitly with tp1.downloadModel=true"
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "model-download-proof-${System.nanoTime()}")
        try {
            val models = ModelManager(root)
            assertFalse(models.isAvailable(Model.Test))
            models.download(Model.Test)
            assertTrue(models.isAvailable(Model.Test))
            assertEquals(Model.Test.artifact.bytes, models.location(Model.Test).length())
            val offline = ModelManager(root, connect = { error("Cached download/inference attempted network access") })
            repeat(2) {
                offline.download(Model.Test)
                val session = InferenceSession.open(offline, Model.Test)
                try {
                    val response = session.infer("Reply with the word hello.")
                    assertTrue(response.text.isNotBlank())
                    assertTrue(response.finishReason in setOf(FinishReason.EOG, FinishReason.LENGTH))
                } finally { session.close() }
            }
        } finally { check(!root.exists() || root.deleteRecursively()) }
    }
}

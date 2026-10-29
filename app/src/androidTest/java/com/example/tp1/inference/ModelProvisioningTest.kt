package com.example.tp1.inference

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Run explicitly with -e tp1.modelInput <target-app-cache-file>. No permissions are elevated. */
@ManualInference
class ModelProvisioningTest {
    @Test fun publishVerifiedLocalArtifact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val path = checkNotNull(InstrumentationRegistry.getArguments().getString("tp1.modelInput")) {
            "Provision explicitly. See LLAMA_CPP_MANUAL_CHECKS.md"
        }
        val input = File(path).canonicalFile
        require(input.parentFile == context.cacheDir.canonicalFile) { "Input must be a direct target-app cache file" }
        val model = InstrumentationRegistry.getArguments().getString("tp1.model")?.let(Model::valueOf) ?: Model.Test
        val destination = ModelManager(context).location(model)
        input.inputStream().use { ModelFile.provision(destination, model.profile.artifact, it) { } }
        assertEquals(model.profile.artifact.bytes, destination.length())
        assertTrue("Remove staging input after successful publication", input.delete())
    }
}

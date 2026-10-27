package com.example.tp1.inference

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class Model(internal val artifact: ModelArtifact) {
    Test(ModelArtifact(
        name = "LFM2.5-230M-Q4_K_M.gguf",
        url = "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/03502067c64ce32ac4fe87b0cec0310a1a13d3e9/LFM2.5-230M-Q4_K_M.gguf",
        bytes = 153406304L,
        sha256 = "7bbd90384d3deffe4c646ec9643b212802d32d4ce417c90a1ec9282100650062"
    ))
}

/** Internal metadata also lets small fixtures exercise the real acquisition path without model weights. */
internal data class ModelArtifact(val name: String, val url: String, val bytes: Long, val sha256: String) {
    init {
        require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
        require(URL(url).protocol == "https" && bytes > 0 && sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/** Explicit acquisition only. Never loads native code, deletes models or replaces a published inode. */
internal class ModelManager(
    private val directory: File,
    private val definition: (Model) -> ModelArtifact = { it.artifact },
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "models"))

    internal fun location(model: Model): File = File(directory, definition(model).name)

    /** Rehashes local bytes. Missing/corrupt artifacts return false; other IO failures are reported. */
    suspend fun isAvailable(model: Model): Boolean = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val file = location(model)
        try {
            ModelFile.verify(file, definition(model)) { coroutine.ensureActive() }
            true
        } catch (_: ModelIntegrityException) {
            false
        } catch (error: FileNotFoundException) {
            if (file.exists()) throw error
            false
        }
    }

    /**
     * Returns after verified publication or cache reuse. Existing invalid files are reported, never replaced.
     * Cancellation is checked between reads and before publication. A blocking network call may take until
     * its connect/read timeout to return; cleanup completes before this operation releases acquisition.
     */
    suspend fun download(model: Model): Unit = withContext(Dispatchers.IO) {
        acquisition.withLock {
            val coroutine = currentCoroutineContext()
            val artifact = definition(model)
            val file = location(model)
            if (file.exists()) {
                ModelFile.verify(file, artifact) { coroutine.ensureActive() }
                return@withLock
            }
            val connection = connect(URL(artifact.url))
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept-Encoding", "identity")
                coroutine.ensureActive()
                val status = connection.responseCode
                coroutine.ensureActive()
                if (status != HttpURLConnection.HTTP_OK) throw IOException("Model download returned HTTP $status")
                connection.inputStream.use { input ->
                    ModelFile.provision(file, artifact, input) { coroutine.ensureActive() }
                }
            } catch (error: IOException) {
                coroutine.ensureActive()
                throw error
            } finally { connection.disconnect() }
        }
    }

    /** Called only on the inference worker. No descriptor escapes to ordinary callers. */
    internal fun open(model: Model, coroutine: CoroutineContext, cancelled: () -> Boolean): ParcelFileDescriptor =
        ModelFile.open(location(model), definition(model)) {
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Model verification cancelled")
        }

    private companion object {
        val acquisition = Mutex()
    }
}

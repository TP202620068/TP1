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

/** Explicit acquisition only. Reuses valid models and repairs corrupt ones without loading native code. */
internal class ModelManager(
    private val directory: File,
    private val definition: (Model) -> ModelProfile = { it.profile },
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
) {
    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, "models"))

    internal fun profile(model: Model): ModelProfile = definition(model)
    internal fun location(model: Model): File = File(directory, profile(model).artifact.name)

    /** Rehashes local bytes. Missing/corrupt artifacts return false; other IO failures are reported. */
    suspend fun isAvailable(model: Model): Boolean = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        val file = location(model)
        try {
            ModelFile.verify(file, profile(model).artifact) { coroutine.ensureActive() }
            true
        } catch (_: ModelIntegrityException) {
            false
        } catch (error: FileNotFoundException) {
            if (file.exists()) throw error
            false
        }
    }

    /**
     * Returns after verified publication or cache reuse. Corrupt files are atomically replaced only after
     * a verified replacement is staged; other local IO failures are reported without attempting repair.
     * Cancellation is checked between reads and before publication. A blocking network call may take until
     * its connect/read timeout to return; cleanup completes before this operation releases acquisition.
     */
    suspend fun download(model: Model): Unit = withContext(Dispatchers.IO) {
        acquisition.withLock {
            val coroutine = currentCoroutineContext()
            val artifact = profile(model).artifact
            val file = location(model)
            if (file.exists()) {
                try {
                    ModelFile.verify(file, artifact) { coroutine.ensureActive() }
                    return@withLock
                } catch (_: ModelIntegrityException) {
                    // Keep the corrupt inode until a verified replacement is ready for publication.
                }
            }
            coroutine.ensureActive()
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
        ModelFile.open(location(model), profile(model).artifact) {
            coroutine.ensureActive()
            if (cancelled()) throw CancellationException("Model verification cancelled")
        }

    private companion object {
        val acquisition = Mutex()
    }
}

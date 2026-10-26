package com.example.tp1.inference

import android.content.Context
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/** One lightweight proof artifact, not a downloader or a model catalog. The caller owns the input stream. */
internal object ModelFile {
    // LiquidAI/LFM2.5-230M-GGUF at 03502067c64ce32ac4fe87b0cec0310a1a13d3e9.
    const val name = "LFM2.5-230M-Q4_K_M.gguf"
    const val bytes = 153406304L
    const val sha256 = "7bbd90384d3deffe4c646ec9643b212802d32d4ce417c90a1ec9282100650062"

    fun location(context: Context) = File(context.noBackupFilesDir, "models/$name")

    /** Setup-only. Never replaces a published file, including one that might be mapped. */
    @Synchronized fun provision(context: Context, input: InputStream): File {
        val destination = location(context)
        val directory = destination.parentFile!!
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create model directory")
        check(!destination.exists()) { "Unload and explicitly remove the old model before provisioning" }
        val staged = File.createTempFile("model-", ".part", directory)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            FileOutputStream(staged).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (size.toLong() > bytes - count) throw IOException("Model exceeds pinned size")
                    output.write(buffer, 0, size)
                    hash.update(buffer, 0, size)
                    count += size
                }
                output.fd.sync()
            }
            verify(count, hash.digest())
            if (!staged.setReadOnly()) throw IOException("Cannot make model read-only")
            // Setup is explicitly single-owner; ATOMIC_MOVE publishes only a complete verified file.
            Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE)
            return destination
        } finally { staged.delete() }
    }

    /** The retained descriptor pins the verified inode even if a path is renamed afterward. */
    fun open(context: Context, coroutine: CoroutineContext, cancelled: () -> Boolean): ParcelFileDescriptor {
        val descriptor = ParcelFileDescriptor.open(location(context), ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            if (descriptor.statSize != bytes) throw IOException("Model size does not match pinned artifact")
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutine.ensureActive()
                    if (cancelled()) throw kotlinx.coroutines.CancellationException("Model verification cancelled")
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (size.toLong() > bytes - count) throw IOException("Model grew during verification")
                    hash.update(buffer, 0, size)
                    count += size
                }
            }
            verify(count, hash.digest())
            return descriptor
        } catch (error: Throwable) { descriptor.close(); throw error }
    }

    private fun verify(count: Long, digest: ByteArray) {
        val expected = sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (count != bytes || !MessageDigest.isEqual(digest, expected)) throw IOException("Model size/SHA-256 mismatch")
    }
}

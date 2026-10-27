package com.example.tp1.inference

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

internal class ModelIntegrityException(message: String) : IOException(message)

/** Shared verification and no-replacement publication. Callers own input streams and cancellation. */
internal object ModelFile {
    fun verify(file: File, artifact: ModelArtifact, checkActive: () -> Unit) {
        FileInputStream(file).use { input ->
            if (input.channel.size() != artifact.bytes) throw ModelIntegrityException("Model size does not match pinned artifact")
            transfer(input, null, artifact, checkActive)
        }
    }

    fun provision(destination: File, artifact: ModelArtifact, input: InputStream, checkActive: () -> Unit): File {
        checkActive()
        val directory = destination.parentFile!!
        Files.createDirectories(directory.toPath())
        val staged = File.createTempFile("model-", ".part", directory)
        var failure: Throwable? = null
        try {
            FileOutputStream(staged).use { output ->
                transfer(input, output, artifact, checkActive)
                output.fd.sync()
            }
            if (!staged.setReadOnly()) throw IOException("Cannot make model read-only")
            checkActive()
            publish(staged, destination, artifact, checkActive)
            return destination
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try { Files.deleteIfExists(staged.toPath()) } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
            }
        }
    }

    /** Every publisher uses this permanent lock file; never delete it while the store is in use. */
    @Synchronized private fun publish(staged: File, destination: File, artifact: ModelArtifact, checkActive: () -> Unit) {
        val directory = destination.parentFile!!
        // The monitor avoids overlapping JVM locks; the file lock coordinates other app processes.
        FileChannel.open(File(directory, ".publish.lock").toPath(), CREATE, WRITE).use { channel ->
            channel.lock().use {
                checkActive()
                // ATOMIC_MOVE may replace a destination. Cooperating publishers must check under the lock.
                if (Files.exists(destination.toPath(), NOFOLLOW_LINKS)) verify(destination, artifact, checkActive)
                else Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE)
                FileChannel.open(directory.toPath(), READ).use { it.force(true) }
            }
        }
    }

    /** The retained descriptor pins the verified inode, rather than trusting a previously checked path. */
    fun open(file: File, artifact: ModelArtifact, checkActive: () -> Unit): ParcelFileDescriptor {
        checkActive()
        val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            if (descriptor.statSize != artifact.bytes) throw ModelIntegrityException("Model size does not match pinned artifact")
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                transfer(input, null, artifact, checkActive)
            }
            checkActive()
            return descriptor
        } catch (error: Throwable) { descriptor.close(); throw error }
    }

    private fun transfer(input: InputStream, output: OutputStream?, artifact: ModelArtifact, checkActive: () -> Unit) {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            checkActive()
            val size = input.read(buffer)
            checkActive()
            if (size < 0) break
            if (size.toLong() > artifact.bytes - count) throw ModelIntegrityException("Model exceeds pinned size")
            output?.write(buffer, 0, size)
            hash.update(buffer, 0, size)
            count += size
        }
        val expected = artifact.sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (count != artifact.bytes || !MessageDigest.isEqual(hash.digest(), expected)) {
            throw ModelIntegrityException("Model size/SHA-256 mismatch")
        }
        checkActive()
    }
}

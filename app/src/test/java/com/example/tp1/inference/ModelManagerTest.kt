package com.example.tp1.inference

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManagerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "verified model fixture 😀".toByteArray()
    private val artifact = ModelArtifact("fixture.gguf", "https://example.invalid/model", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })

    private fun manager(directory: File, connection: () -> HttpURLConnection) =
        ModelManager(directory, { Model.Test.profile.copy(artifact = artifact) }, { connection() })

    @Test fun verifiedDownloadAndOfflineCacheReuse() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val connection = Connection(ByteArrayInputStream(bytes))
        val models = manager(directory) { connection }
        assertFalse(models.isAvailable(Model.Test))
        models.download(Model.Test)
        assertTrue(models.isAvailable(Model.Test))
        assertArrayEquals(bytes, models.location(Model.Test).readBytes())
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(15_000, connection.readTimeout)
        assertEquals("identity", connection.getRequestProperty("Accept-Encoding"))
        assertTrue(connection.disconnected)
        val offline = manager(directory) { error("Cached acquisition must not connect") }
        offline.download(Model.Test)
        assertTrue(offline.isAvailable(Model.Test))
        assertEquals(setOf(artifact.name, ".publish.lock"), directory.list()!!.toSet())
    }

    @Test fun actualSizeAndHashAreRequiredRegardlessOfHeaders() = runBlocking<Unit> {
        for (invalid in listOf(bytes.dropLast(1).toByteArray(), bytes + 1, ByteArray(bytes.size))) {
            val directory = temporary.newFolder()
            val connection = Connection(ByteArrayInputStream(invalid))
            val models = manager(directory) { connection }
            try { models.download(Model.Test); fail("Invalid artifact accepted") } catch (_: ModelIntegrityException) { }
            assertFalse(models.isAvailable(Model.Test))
            assertTrue(directory.listFiles()!!.isEmpty())
            assertTrue(connection.disconnected)
        }
    }

    @Test fun corruptSizeAndHashAreRepairedWithoutChangingOpenInodes() = runBlocking<Unit> {
        for (invalid in listOf(bytes.dropLast(1).toByteArray(), ByteArray(bytes.size))) {
            val directory = temporary.newFolder()
            val connection = Connection(ByteArrayInputStream(bytes))
            val models = manager(directory) { connection }
            val file = models.location(Model.Test)
            file.writeBytes(invalid)
            val originalInode = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
            assertFalse(models.isAvailable(Model.Test))
            FileInputStream(file).use { original ->
                models.download(Model.Test)
                assertArrayEquals(invalid, original.readBytes())
            }
            assertArrayEquals(bytes, file.readBytes())
            assertNotEquals(originalInode, Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
            assertTrue(models.isAvailable(Model.Test))
            assertTrue(connection.disconnected)
            assertEquals(setOf(artifact.name, ".publish.lock"), directory.list()!!.toSet())
        }
    }

    @Test fun failedRepairRetainsCorruptArtifactAndAllowsRetry() = runBlocking<Unit> {
        val failures = listOf(
            Connection(ByteArrayInputStream(bytes), status = 503),
            Connection(object : InputStream() { override fun read(): Int = throw SocketTimeoutException("Fixture timeout") }),
            Connection(ByteArrayInputStream(bytes.dropLast(1).toByteArray())),
            Connection(ByteArrayInputStream(ByteArray(bytes.size)))
        )
        for (connection in failures) {
            val directory = temporary.newFolder()
            val models = manager(directory) { connection }
            val file = models.location(Model.Test)
            val invalid = ByteArray(bytes.size)
            file.writeBytes(invalid)
            val originalInode = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
            try { models.download(Model.Test); fail("Failed repair accepted") } catch (_: IOException) { }
            assertArrayEquals(invalid, file.readBytes())
            assertEquals(originalInode, Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey())
            assertEquals(setOf(artifact.name), directory.list()!!.toSet())
            assertTrue(connection.disconnected)
            val retry = manager(directory) { Connection(ByteArrayInputStream(bytes)) }
            retry.download(Model.Test)
            assertTrue(retry.isAvailable(Model.Test))
        }
    }

    @Test fun localIOFailureDoesNotAttemptRepair() = runBlocking<Unit> {
        val directory = temporary.newFolder()
        val models = manager(directory) { error("Local IO failure must not trigger a download") }
        assertTrue(models.location(Model.Test).mkdir())
        try { models.download(Model.Test); fail("Local IO failure ignored") } catch (_: IOException) { }
        assertTrue(models.location(Model.Test).isDirectory)
    }

    @Test fun httpTimeoutAndInterruptedCopyCleanUpAndAllowRetry() = runBlocking<Unit> {
        val failures = listOf(
            Connection(ByteArrayInputStream(bytes), status = 503),
            Connection(object : InputStream() { override fun read(): Int = throw SocketTimeoutException("Fixture timeout") }),
            Connection(object : InputStream() {
                private var first = true
                override fun read(): Int = throw IOException("Fixture interruption")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (!first) throw IOException("Fixture interruption")
                    first = false
                    buffer[offset] = bytes[0]
                    return 1
                }
            })
        )
        for (connection in failures) {
            val directory = temporary.newFolder()
            try { manager(directory) { connection }.download(Model.Test); fail("IO failure accepted") } catch (_: IOException) { }
            assertTrue(connection.disconnected)
            assertTrue(directory.listFiles()!!.isEmpty())
            manager(directory) { Connection(ByteArrayInputStream(bytes)) }.download(Model.Test)
        }
    }

    @Test fun cancellationWaitsForBlockedReadAndRemovesStagingBeforeReturning() = runBlocking<Unit> {
        for (repair in listOf(false, true)) {
            val directory = temporary.newFolder()
            val destination = File(directory, artifact.name)
            val invalid = ByteArray(bytes.size)
            if (repair) destination.writeBytes(invalid)
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            var inputClosed = false
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    entered.complete(Unit)
                    check(release.await(10, TimeUnit.SECONDS))
                    return super.read(buffer, offset, length)
                }
                override fun close() { inputClosed = true; super.close() }
            }
            val connection = Connection(input)
            val pending = async(Dispatchers.IO) { manager(directory) { connection }.download(Model.Test) }
            try {
                withTimeout(10_000) { entered.await() }
                pending.cancel()
                assertFalse("Blocking work must retain acquisition until its read returns", pending.isCompleted)
            } finally {
                release.countDown()
                withTimeout(10_000) { pending.cancelAndJoin() }
            }
            assertTrue(inputClosed)
            assertTrue(connection.disconnected)
            assertEquals(if (repair) setOf(artifact.name) else emptySet<String>(), directory.list()!!.toSet())
            if (repair) assertArrayEquals(invalid, destination.readBytes())
            val retry = manager(directory) { Connection(ByteArrayInputStream(bytes)) }
            retry.download(Model.Test)
            assertTrue(retry.isAvailable(Model.Test))
        }
    }

    @Test fun concurrentManagerInstancesAcquireOnlyOnce() = runBlocking<Unit> {
        for (repair in listOf(false, true)) {
            val directory = temporary.newFolder()
            if (repair) File(directory, artifact.name).writeBytes(ByteArray(bytes.size))
            val calls = AtomicInteger()
            val entered = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val connect = {
                calls.incrementAndGet()
                Connection(object : ByteArrayInputStream(bytes) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        entered.complete(Unit)
                        check(release.await(10, TimeUnit.SECONDS))
                        return super.read(buffer, offset, length)
                    }
                })
            }
            val first = async(Dispatchers.IO) { manager(directory, connect).download(Model.Test) }
            val second = async(Dispatchers.IO) { manager(directory, connect).download(Model.Test) }
            try {
                withTimeout(10_000) { entered.await() }
            } finally { release.countDown() }
            withTimeout(10_000) { first.await(); second.await() }
            assertEquals(1, calls.get())
            assertEquals(setOf(artifact.name, ".publish.lock"), directory.list()!!.toSet())
        }
    }

    @Test fun anotherPublisherWinsWithoutReplacingItsInode() {
        for (repair in listOf(false, true)) {
            val directory = temporary.newFolder()
            val destination = File(directory, artifact.name)
            if (repair) destination.writeBytes(ByteArray(bytes.size))
            var winningInode: Any? = null
            val input = object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    if (count < 0) {
                        ModelFile.provision(destination, artifact, ByteArrayInputStream(bytes)) { }
                        winningInode = Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java).fileKey()
                    }
                    return count
                }
            }
            ModelFile.provision(destination, artifact, input) { }
            assertNotNull(winningInode)
            assertEquals(winningInode, Files.readAttributes(destination.toPath(), BasicFileAttributes::class.java).fileKey())
            assertArrayEquals(bytes, destination.readBytes())
            assertEquals(setOf(artifact.name, ".publish.lock"), directory.list()!!.toSet())
        }
    }

    @Test fun unavailableStorageClosesInputAndDoesNotPublish() = runBlocking<Unit> {
        val notDirectory = temporary.newFile()
        var closed = false
        val connection = Connection(object : ByteArrayInputStream(bytes) {
            override fun close() { closed = true; super.close() }
        })
        try { manager(notDirectory) { connection }.download(Model.Test); fail("Unusable storage accepted") } catch (_: IOException) { }
        assertTrue(closed)
        assertTrue(connection.disconnected)
        assertEquals(0L, notDirectory.length())
    }

    private class Connection(private val input: InputStream, private val status: Int = 200) :
        HttpURLConnection(URL("https://example.invalid/model")) {
        var disconnected = false
        override fun connect() { }
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getInputStream() = input
        // Intentionally wrong: headers are never trusted as artifact verification.
        override fun getContentLengthLong() = 1L
    }
}

package com.example.tp1.extraction

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract.Document
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.ModelManager
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DocumentExtractorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val provider = Uri.parse("content://com.example.tp1.test.documents")
    private val resolver = context.contentResolver
    private val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    private val store = SourceStore(context)
    private lateinit var source: SelectedSource
    private lateinit var entry: BrowserEntry

    private fun fixtureCall(method: String, extras: Bundle? = null): Bundle {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        return try {
            checkNotNull(resolver.call(provider, method, null, extras))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun configure(bytes: ByteArray = "name,value\nTP1,fixture\n".toByteArray(), name: String = "fixture.csv",
                          mime: String = "text/csv", size: Long = -1, slow: Boolean = false): Uri {
        val result = fixtureCall("fixture-extraction", Bundle().apply {
            putByteArray("bytes", bytes)
            putString("name", name)
            putString("mime", mime)
            putLong("size", size)
            putBoolean("slow", slow)
        })
        return Uri.parse(result.getString("uri"))
    }

    private fun select(uri: Uri) {
        val existing = store.restore().sources
        source = store.add(existing, listOf(uri.toString()), SourceKind.Document, flags).sources.single { it.record.uri == uri.toString() }
        entry = DocumentBrowser(resolver).root(source)
    }

    @Before fun prepare() { select(configure()) }

    @After fun cleanup() {
        if (::source.isInitialized) store.remove(store.restore().sources, source.record.uri)
    }

    private fun success(output: File, text: String = "Completed Markdown") = NativeConversion("ok", "", "csv", longArrayOf(), 0).also {
        output.writeText(text, Charsets.UTF_8)
    }

    private suspend fun failure(block: suspend () -> Unit): ExtractionFailure {
        try { block() } catch (error: ExtractionFailure) { return error }
        error("Expected extraction failure")
    }

    @Test fun nativeCsvOfficeAndTextPdfPublishReadableUnicodeAndProvenance() = runBlocking<Unit> {
        val extractor = DocumentExtractor(context)
        for ((name, expected) in listOf("sample.csv" to "日本語", "sample.rtf" to "TP1 office fixture")) {
            val bytes = instrumentation.context.assets.open("extraction/$name").use { it.readBytes() }
            configure(bytes, name)
            val artifact = extractor.extract(source, entry)
            assertTrue(artifact.markdown.readText().contains(expected))
            val provenance = JSONObject(artifact.provenance.readText())
            assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).hex(), provenance.getString("inputSha256"))
            assertEquals(MessageDigest.getInstance("SHA-256").digest(artifact.markdown.readBytes()).hex(), provenance.getString("markdownSha256"))
            assertEquals(source.record.uri, provenance.getString("sourceUri"))
            assertTrue(provenance.isNull("observedSize"))
        }
        val pdf = PdfDocument()
        val bytes = try {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(300, 300, 1).create())
            page.canvas.drawText("TP1 text PDF fixture", 20f, 50f, Paint().apply { textSize = 16f })
            pdf.finishPage(page)
            ByteArrayOutputStream().use { stream -> pdf.writeTo(stream); stream.toByteArray() }
        } finally { pdf.close() }
        configure(bytes, "suffixless", "application/pdf")
        repeat(3) {
            val artifact = extractor.extract(source, entry)
            assertTrue(artifact.markdown.readText().contains("TP1 text PDF fixture"))
            assertEquals("pdf", JSONObject(artifact.provenance.readText()).getString("format"))
        }
        val previous = ExtractionArtifacts.current(context, source, entry)
        configure("Unsupported fixture".toByteArray(), "unsupported.bin", "application/octet-stream")
        assertEquals("unsupported", failure { extractor.extract(source, entry) }.code)
        assertEquals(previous, ExtractionArtifacts.current(context, source, entry))
    }

    @Test fun nativeMixedPdfRequiresOcrWithOneBasedPageNumbers() = runBlocking<Unit> {
        val pdf = PdfDocument()
        val scan = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val bytes = try {
            val paint = Paint().apply { color = Color.BLACK; textSize = 16f }
            Canvas(scan).apply {
                drawColor(Color.WHITE)
                repeat(10) { drawText("Scanned fixture line $it", 10f, 30f + it * 24f, paint) }
            }
            val text = pdf.startPage(PdfDocument.PageInfo.Builder(300, 300, 1).create())
            text.canvas.drawText("Readable first page", 20f, 50f, paint)
            pdf.finishPage(text)
            val image = pdf.startPage(PdfDocument.PageInfo.Builder(300, 300, 2).create())
            image.canvas.drawBitmap(scan, 0f, 0f, null)
            pdf.finishPage(image)
            ByteArrayOutputStream().use { stream -> pdf.writeTo(stream); stream.toByteArray() }
        } finally { pdf.close(); scan.recycle() }
        configure(bytes, "mixed.pdf", "application/pdf")
        val error = failure { DocumentExtractor(context).extract(source, entry) }
        assertEquals("needsOcr", error.code)
        assertEquals(2L, error.pageCount)
        assertArrayEquals(longArrayOf(2), error.ocrPages)
        assertNull(ExtractionArtifacts.current(context, source, entry))
    }

    @Test @ManualInference fun extractedMarkdownChunksAndEmbedsWithExactTokenReport() = runBlocking<Unit> {
        val rtf = "{\\rtf1\\ansi " +
            "The laptop warranty lasts two years from purchase. Keep the receipt for repairs.\\par ".repeat(30) + "}"
        configure(rtf.toByteArray(), "chunking.rtf", "application/rtf")
        val artifact = DocumentExtractor(context).extract(source, entry)
        val markdown = artifact.markdown.readText(Charsets.UTF_8)
        assertTrue(markdown.contains("warranty"))
        val session = EmbeddingSession.open(ModelManager(context))
        try {
            val chunks = session.chunkDocument(markdown)
            assertTrue(chunks.size > 1)
            reportChunkTokens("anydoc-rtf", chunks, session.maxInputTokens)
            for (chunk in chunks) {
                assertEquals(session.countDocumentTokens(chunk.text), chunk.inputTokens)
                assertTrue(chunk.inputTokens <= session.maxInputTokens)
                val vector = session.embedDocument(chunk.text)
                assertEquals(512, vector.size)
                assertEquals(1.0, vector.sumOf { it.toDouble() * it }, 1e-5)
            }
        } finally { session.close() }
    }

    @Test fun actualByteLimitsIgnoreUnknownAndIncorrectProviderSizes() = runBlocking<Unit> {
        val bytes = "name,value\nTP1,fixture\n".toByteArray()
        for (reported in listOf(-1L, 0L)) {
            configure(bytes, size = reported)
            val extractor = DocumentExtractor(context, ExtractionLimits(bytes.size.toLong() - 1, 100)) { _, _, _, _ ->
                error("Oversized input reached native conversion")
            }
            assertEquals("resourceLimit", failure { extractor.extract(source, entry) }.code)
        }
        val artifact = DocumentExtractor(context, ExtractionLimits(bytes.size.toLong(), 100)) { _, hint, output, _ ->
            assertEquals("csv", hint)
            success(output)
        }.extract(source, entry)
        assertEquals(bytes.size.toLong(), JSONObject(artifact.provenance.readText()).getLong("inputBytes"))
        assertTrue(ExtractionArtifacts.operations(context).listFiles().orEmpty().isEmpty())
    }

    @Test fun failuresAndOversizedOutputPreservePreviousGeneration() = runBlocking<Unit> {
        val original = DocumentExtractor(context) { _, _, output, _ -> success(output, "Previous valid text") }.extract(source, entry)
        val ocr = DocumentExtractor(context) { _, _, _, _ -> NativeConversion("needsOcr", "Scanned pages", "pdf", longArrayOf(1, 3), 4) }
        val error = failure { ocr.extract(source, entry) }
        assertArrayEquals(longArrayOf(1, 3), error.ocrPages)
        assertEquals(4L, error.pageCount)
        val oversized = DocumentExtractor(context, ExtractionLimits(outputBytes = 1)) { _, _, output, _ -> success(output) }
        assertEquals("resourceLimit", failure { oversized.extract(source, entry) }.code)
        assertEquals(original, ExtractionArtifacts.current(context, source, entry))
        assertEquals("Previous valid text", original.markdown.readText())
        assertTrue(ExtractionArtifacts.operations(context).listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellationDuringCopyClosesProviderAndNeverCallsNative() = runBlocking<Unit> {
        configure(ByteArray(256 * 1024) { 'a'.code.toByte() }, slow = true)
        val called = AtomicBoolean(false)
        val extractor = DocumentExtractor(context) { _, _, output, _ -> called.set(true); success(output) }
        val job = async(Dispatchers.IO) { extractor.extract(source, entry) }
        try {
            withTimeout(5_000) {
                while (ExtractionArtifacts.operations(context).listFiles().orEmpty().none { File(it, "input").length() > 0 }) delay(10)
            }
        } finally { job.cancelAndJoin() }
        assertFalse(called.get())
        assertTrue(ExtractionArtifacts.operations(context).listFiles().orEmpty().isEmpty())
    }

    @Test fun cancellingNativeKeepsInputAndSerializationUntilReturn() = runBlocking<Unit> {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val input = AtomicReference<File>()
        val first = async(Dispatchers.IO) {
            DocumentExtractor(context) { staged, _, output, _ ->
                input.set(staged)
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                success(output, "Cancelled result")
            }.extract(source, entry)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            first.cancel()
            assertTrue(input.get().isFile)
            val second = async(Dispatchers.IO) {
                DocumentExtractor(context) { _, _, output, _ -> secondEntered.countDown(); success(output, "New result") }.extract(source, entry)
            }
            assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS))
            release.countDown()
            first.join()
            assertEquals("New result", second.await().markdown.readText())
            assertFalse(input.get().exists())
        } finally {
            release.countDown()
            first.cancelAndJoin()
        }
    }

    @Test fun sourceRemovalDeletesArtifactsAndReaddCannotReviveRunningResult() = runBlocking<Unit> {
        val original = DocumentExtractor(context) { _, _, output, _ -> success(output) }.extract(source, entry)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pending = async(Dispatchers.IO) {
            DocumentExtractor(context) { _, _, output, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                success(output, "Stale result")
            }.extract(source, entry)
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            store.remove(store.restore().sources, source.record.uri)
            assertFalse(original.markdown.exists())
            select(configure())
            release.countDown()
            try { pending.await(); fail("Removed source result was published") } catch (_: CancellationException) { }
            assertNull(ExtractionArtifacts.current(context, source, entry))
        } finally {
            release.countDown()
            pending.cancelAndJoin()
        }
    }

    @Test fun revokedGrantAndInvalidSelectionsAreRejected() = runBlocking<Unit> {
        val browser = DocumentBrowser(resolver)
        assertThrows(SecurityException::class.java) { browser.openRead(source, entry.copy(sourceUri = "content://other")) }
        assertThrows(SecurityException::class.java) { browser.openRead(source, entry.copy(uri = entry.uri + "-sibling")) }
        assertThrows(SecurityException::class.java) { browser.openRead(source, entry.copy(directory = true)) }
        configure(mime = Document.MIME_TYPE_DIR)
        assertThrows(FileNotFoundException::class.java) { browser.openRead(source, entry) }
        configure()
        val original = DocumentExtractor(context) { _, _, output, _ -> success(output) }.extract(source, entry)
        val revoked = DocumentExtractor(context) { _, _, output, _ ->
            fixtureCall("fixture-extraction-revoke")
            success(output, "Unauthorized result")
        }
        try { revoked.extract(source, entry); fail("Revoked source result was published") } catch (_: SecurityException) { }
        assertTrue(original.markdown.isFile)
        assertEquals("Completed Markdown", original.markdown.readText())
        assertThrows(SecurityException::class.java) { browser.openRead(source, entry) }
    }

    @Test fun failedArtifactDeletionCannotExposeOldGenerationForSavedSource() = runBlocking<Unit> {
        val original = DocumentExtractor(context) { _, _, output, _ -> success(output, "Retired text") }.extract(source, entry)
        ExtractionArtifacts.removed(context, source.record.uri) { false }
        assertTrue(original.markdown.isFile)
        // The URI is still saved and readable, as after re-addition. Disk invalidation must hide old content.
        assertNull(ExtractionArtifacts.current(context, source, entry))
        val marker = File(context.noBackupFilesDir, "extraction-v1/invalidated/" +
            MessageDigest.getInstance("SHA-256").digest(source.record.uri.toByteArray()).hex())
        assertTrue(marker.isFile)
        val fresh = DocumentExtractor(context) { _, _, output, _ -> success(output, "Fresh text") }.extract(source, entry)
        assertFalse(original.markdown.exists())
        assertFalse(marker.exists())
        assertEquals("Fresh text", fresh.markdown.readText())
        assertEquals(fresh, ExtractionArtifacts.current(context, source, entry))
    }

    @Test fun uncertainRemovalWithUnknownRecordsCannotReviveOldArtifacts() = runBlocking<Unit> {
        val original = DocumentExtractor(context) { _, _, output, _ -> success(output, "Retired text") }.extract(source, entry)
        val file = File(context.filesDir, "sources.json")
        val saved = file.readText()
        val unknown = JSONObject().put("future", "preserve")
        var failAfterCommit = false
        var failRead = false
        val storage = object : AtomicFile(file) {
            override fun finishWrite(stream: FileOutputStream?) {
                super.finishWrite(stream)
                if (failAfterCommit) { failAfterCommit = false; failRead = true }
            }

            override fun openRead(): FileInputStream {
                if (failRead) { failRead = false; throw IOException("Simulated post-commit verification failure") }
                return super.openRead()
            }
        }
        try {
            file.writeText(JSONArray(saved).put(unknown).toString())
            val uncertain = SourceStore(context, storage = storage)
            val existing = uncertain.restore().sources
            failAfterCommit = true
            assertThrows(IOException::class.java) { uncertain.remove(existing, source.record.uri) }
            val restored = uncertain.restore()
            assertTrue(restored.damagedRecords)
            assertFalse(restored.sources.any { it.record.uri == source.record.uri })
            select(configure())
            assertNull(ExtractionArtifacts.current(context, source, entry))
            assertFalse(original.markdown.exists())
            val records = JSONArray(file.readText())
            assertEquals("preserve", records.getJSONObject(records.length() - 1).getString("future"))
            val fresh = DocumentExtractor(context) { _, _, output, _ -> success(output, "Fresh text") }.extract(source, entry)
            assertEquals("Fresh text", fresh.markdown.readText())
        } finally {
            file.writeText(saved)
        }
    }

    @Test fun providerNamesCannotChooseStagingPathsOrPassUnsafeHints() = runBlocking<Unit> {
        for ((name, expected) in listOf("../reports/UPPER.CSV" to "csv", "report.csv/../../payload" to null, "report.verylongextension" to null)) {
            configure(name = name)
            DocumentExtractor(context) { input, hint, output, _ ->
                assertEquals("input", input.name)
                assertEquals("document.md", output.name)
                assertEquals(expected, hint)
                success(output)
            }.extract(source, entry)
        }
    }
}

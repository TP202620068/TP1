package com.example.tp1.extraction

import android.content.Context
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.sources.SelectedSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class ExtractionLimits(val inputBytes: Long = 32L * 1024 * 1024, val outputBytes: Long = 16L * 1024 * 1024) {
    init { require(inputBytes > 0 && outputBytes in 1 until Int.MAX_VALUE.toLong()) }
}

internal class ExtractionFailure(
    val code: String,
    detail: String,
    val format: String? = null,
    /** OCR page numbers are 1-based. Zero pageCount means unavailable. */
    val ocrPages: LongArray = longArrayOf(),
    val pageCount: Long = 0
) : IOException(detail)

internal data class ExtractedDocument(val markdown: String, val displayName: String, val inputSha256: String)

/** One concrete extractor. The function parameter lets instrumentation control the native-call boundary. */
internal class DocumentExtractor(
    context: Context,
    private val limits: ExtractionLimits = ExtractionLimits(),
    private val convert: (File, String?, File, ExtractionLimits) -> NativeConversion = { input, hint, output, bounds ->
        AnyDocNative.convert(input.absolutePath, hint, output.absolutePath, bounds)
    }
) {
    private val context = context.applicationContext
    private val browser = DocumentBrowser(this.context.contentResolver)

    suspend fun extract(source: SelectedSource, entry: BrowserEntry): ExtractedDocument = withContext(Dispatchers.IO) {
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        val ticket = ExtractionWork.begin(context, source, entry)
        ExtractionWork.permit.withLock {
            ExtractionWork.checkCurrent(context, ticket)
            operationContext.ensureActive()
            val operation = File(ExtractionWork.operations(context), UUID.randomUUID().toString())
            if (!operation.mkdirs()) throw IOException("Cannot create extraction staging directory")
            try {
                val input = File(operation, "input")
                val output = File(operation, "output")
                val inputHash = MessageDigest.getInstance("SHA-256")
                var inputBytes = 0L
                val metadata = browser.openRead(source, entry).use { document ->
                    FileOutputStream(input).use { staging ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            operationContext.ensureActive()
                            val count = document.stream.read(buffer)
                            if (count < 0) break
                            if (count.toLong() > limits.inputBytes - inputBytes) {
                                throw ExtractionFailure("resourceLimit", "Input exceeds app byte limit")
                            }
                            staging.write(buffer, 0, count)
                            inputHash.update(buffer, 0, count)
                            inputBytes += count
                        }
                    }
                    document.metadata
                }
                operationContext.ensureActive()
                ExtractionWork.checkCurrent(context, ticket)
                val hint = metadata.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    .takeIf { it.length in 1..8 && it.all { char -> char in 'a'..'z' || char in '0'..'9' } }
                // Cancellation cannot interrupt this synchronous call. Keep the permit and files until it returns.
                val result = convert(input, hint, output, limits)
                operationContext.ensureActive()
                if (result.code != "ok") throw ExtractionFailure(result.code, result.detail, result.format, result.ocrPages, result.pageCount)
                if (result.format == null || !output.isFile) throw ExtractionFailure("native", "Conversion returned an incomplete result")
                if (output.length() > limits.outputBytes) throw ExtractionFailure("resourceLimit", "Markdown exceeds app byte limit")
                val bytes = output.inputStream().use { it.readNBytes(limits.outputBytes.toInt() + 1) }
                if (bytes.size.toLong() > limits.outputBytes) throw ExtractionFailure("resourceLimit", "Markdown exceeds app byte limit")
                val markdown = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                operationContext.ensureActive()
                ExtractionWork.checkCurrent(context, ticket)
                ExtractedDocument(markdown, metadata.name, inputHash.digest().hex())
            } finally {
                ExtractionWork.discard(operation)
            }
        }
    }

}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

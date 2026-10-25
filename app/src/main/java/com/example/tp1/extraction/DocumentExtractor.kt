package com.example.tp1.extraction

import android.content.Context
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.sources.SelectedSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class ExtractionLimits(val inputBytes: Long = 32L * 1024 * 1024, val outputBytes: Long = 16L * 1024 * 1024) {
    init { require(inputBytes > 0 && outputBytes > 0) }
}

internal class ExtractionFailure(
    val code: String,
    detail: String,
    val format: String? = null,
    /** OCR page numbers are 1-based. Zero pageCount means unavailable. */
    val ocrPages: LongArray = longArrayOf(),
    val pageCount: Long = 0
) : IOException(detail)

internal data class ExtractionArtifact(val markdown: File, val provenance: File)

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

    suspend fun extract(source: SelectedSource, entry: BrowserEntry): ExtractionArtifact = withContext(Dispatchers.IO) {
        val operationContext = currentCoroutineContext()
        operationContext.ensureActive()
        val ticket = ExtractionArtifacts.begin(context, source, entry)
        ExtractionArtifacts.permit.withLock {
            ExtractionArtifacts.checkCurrent(context, ticket)
            operationContext.ensureActive()
            val operation = File(ExtractionArtifacts.operations(context), UUID.randomUUID().toString())
            if (!operation.mkdirs()) throw IOException("Cannot create extraction staging directory")
            try {
                val input = File(operation, "input")
                val generation = File(operation, "generation")
                if (!generation.mkdir()) throw IOException("Cannot create extraction generation")
                val output = File(generation, "document.md")
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
                ExtractionArtifacts.checkCurrent(context, ticket)
                val hint = metadata.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    .takeIf { it.length in 1..8 && it.all { char -> char in 'a'..'z' || char in '0'..'9' } }
                // Cancellation cannot interrupt this synchronous call. Keep the permit and files until it returns.
                val result = convert(input, hint, output, limits)
                operationContext.ensureActive()
                if (result.code != "ok") throw ExtractionFailure(result.code, result.detail, result.format, result.ocrPages, result.pageCount)
                if (result.format == null || !output.isFile) throw ExtractionFailure("native", "Conversion returned an incomplete result")
                if (output.length() > limits.outputBytes) throw ExtractionFailure("resourceLimit", "Markdown exceeds app byte limit")
                val outputHash = MessageDigest.getInstance("SHA-256")
                output.inputStream().use { stream ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count = stream.read(buffer)
                    while (count >= 0) {
                        operationContext.ensureActive()
                        outputHash.update(buffer, 0, count)
                        count = stream.read(buffer)
                    }
                }
                val provenance = JSONObject()
                    .put("schema", 1)
                    .put("sourceUri", source.record.uri)
                    .put("documentUri", entry.uri)
                    .put("displayName", metadata.name)
                    .put("observedMime", metadata.mimeType ?: JSONObject.NULL)
                    .put("observedSize", metadata.size ?: JSONObject.NULL)
                    .put("observedModified", metadata.modified ?: JSONObject.NULL)
                    .put("inputBytes", inputBytes)
                    .put("inputSha256", inputHash.digest().hex())
                    .put("format", result.format)
                    .put("recipe", recipe)
                    .put("markdownBytes", output.length())
                    .put("markdownSha256", outputHash.digest().hex())
                FileOutputStream(File(generation, "provenance.json")).use { stream ->
                    stream.write(provenance.toString().toByteArray(Charsets.UTF_8))
                    stream.fd.sync()
                }
                ExtractionArtifacts.publish(context, ticket, generation, operationContext)
            } finally {
                ExtractionArtifacts.discard(operation)
            }
        }
    }

    private companion object {
        const val recipe = "tp1-v1/anydoc-0.2.4/pdf-inspector-1.25.2"
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

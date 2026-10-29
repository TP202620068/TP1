package com.example.tp1.extraction

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.inference.EmbeddingSession
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.ModelManager
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exact counts require the provisioned, pinned Jina GGUF. No network and no mocked token counts. */
@ManualInference
class MarkdownChunkingTest {
    @Test fun approximationReportsActualJinaCountsAndEmbedsBoundedChunks() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = EmbeddingSession.open(ModelManager(context))
        try {
            val cases = linkedMapOf(
                "spanish" to ("# Garantía\n\n" +
                    "La garantía de la laptop dura dos años desde la compra. Conserva la factura para solicitar una reparación. ".repeat(25)),
                "unicode" to ("# Notas\n\n" + "日本語 😀 garantía, número 12345 y café. ".repeat(60)),
                "table" to ("# Precios\n\n| Producto | Precio |\n| --- | --- |\n" +
                    "| Computadora portátil con garantía de dos años | 1200 EUR |\n".repeat(35)),
                "identifiers" to ("# Referencias\n\n" + "X9-z7_Q2/1234567890".repeat(160))
            )
            for ((label, markdown) in cases) {
                val chunks = session.chunkDocument(markdown)
                assertTrue(chunks.size > 1)
                reportChunkTokens(label, chunks, session.maxInputTokens)
                val source = markdown.toByteArray(Charsets.UTF_8)
                val covered = BooleanArray(source.size)
                for (chunk in chunks) {
                    assertEquals(session.countDocumentTokens(chunk.text), chunk.inputTokens)
                    assertTrue(chunk.inputTokens <= 1024)
                    assertArrayEquals(chunk.text.toByteArray(), source.copyOfRange(chunk.startByte, chunk.endByte))
                    for (index in chunk.startByte until chunk.endByte) covered[index] = true
                }
                assertTrue(String(source.indices.filter { !covered[it] }.map { source[it] }.toByteArray()).isBlank())
                val vector = session.embedDocument(chunks.first().text)
                assertEquals(512, vector.size)
                assertEquals(1.0, vector.sumOf { it.toDouble() * it }, 1e-5)
            }
        } finally { session.close() }
    }
}

/** Counts only. Never log source text or URIs. Estimates exclude prefix/EOS; exact counts include both. */
internal fun reportChunkTokens(label: String, chunks: List<MarkdownChunk>, limit: Int) {
    fun report(message: String) { Log.i("TP1ChunkTokens", message); println(message) }
    report("$label: estimatedTextTokens=characters/3.5; exactInputTokens includes Document: and EOS")
    chunks.forEachIndexed { index, chunk ->
        val characters = chunk.text.codePointCount(0, chunk.text.length)
        report(String.format(Locale.ROOT,
            "%s chunk=%d coreChars=%d finalChars=%d estimatedTextTokens=%.1f exactInputTokens=%d remaining=%d",
            label, index, chunk.coreCharacters, characters, characters / 3.5, chunk.inputTokens, limit - chunk.inputTokens))
    }
    require(chunks.isNotEmpty())
    report(String.format(Locale.ROOT, "%s summary chunks=%d exactTokens min=%d average=%.1f max=%d", label, chunks.size,
        chunks.minOf { it.inputTokens }, chunks.map { it.inputTokens }.average(), chunks.maxOf { it.inputTokens }))
}

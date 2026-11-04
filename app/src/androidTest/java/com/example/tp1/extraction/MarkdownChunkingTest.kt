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
    @Test fun symmetricTokenGroupsReportCoreContextAndCompletionOvershoot() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = EmbeddingSession.open(ModelManager(context))
        try {
            val cases = linkedMapOf(
                "spanish" to ("# Garantía\n\n" +
                    "La garantía de la laptop dura dos años desde la compra. Conserva la factura para solicitar una reparación. ".repeat(25)),
                "unicode" to ("# Notas\n\n" + "日本語 😀 garantía, número 12345 y café. ".repeat(60)),
                "table" to ("# Precios\n\n| Producto | Precio |\n| --- | --- |\n" +
                    "| Computadora portátil con garantía de dos años | 1200 EUR |\n".repeat(35)),
                "identifiers" to ("# Referencias\n\n" + "X9-z7_Q2/1234567890".repeat(160)),
                "identifier-list" to ("# Referencias\n\n" + "- X9-z7_Q2/1234567890\n".repeat(100)),
                "long-sentence" to ("# Informe\n\n" + "a ".repeat(350) + ".\n\n" +
                    "La factura sirve para solicitar una reparación. ".repeat(40))
            )
            for ((label, markdown) in cases) {
                val chunks = session.chunkDocument(markdown)
                assertTrue(chunks.size > 1)
                val counts = reportChunkTokens(label, markdown, chunks, session)
                if (label in listOf("spanish", "unicode", "table", "identifier-list")) {
                    assertTrue("Non-tail cores should reach the 224-token target", counts.dropLast(1).all { it.core >= 224 })
                    assertTrue("Interior left context should add at least 16 tokens", counts.drop(1).all { it.left >= 16 })
                    assertTrue("Right context should reach 16 tokens or the document end", counts.indices.all {
                        counts[it].right >= 16 || chunks[it].endByte == markdown.toByteArray().size
                    })
                }
                if (label == "long-sentence") assertTrue("Preserve a complete sentence above the core target", counts.first().core > 300)
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

    @Test fun smallContextsStillSplitDenseUnicodeAndRespectExactLimits() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = EmbeddingSession.open(ModelManager(context), maxInputTokens = 64)
        try {
            for ((label, markdown) in listOf("small-unicode" to "🔥界".repeat(100), "small-identifiers" to "x9/".repeat(150))) {
                val chunks = session.chunkDocument(markdown)
                assertTrue(chunks.size > 1)
                reportChunkTokens(label, markdown, chunks, session)
                val source = markdown.toByteArray()
                val cores = chunks.flatMap { source.copyOfRange(it.coreStartByte, it.coreEndByte).toList() }.toByteArray()
                assertArrayEquals(source, cores)
                assertTrue(chunks.all { it.inputTokens <= 64 })
                assertEquals(512, session.embedDocument(chunks.first().text).size)
            }
        } finally { session.close() }
    }
}

internal data class ChunkTokenCounts(val core: Int, val left: Int, val right: Int)

/** Counts only. Never log source text or URIs. Estimates exclude prefix/EOS; exact counts include both. */
internal suspend fun reportChunkTokens(
    label: String, markdown: String, chunks: List<MarkdownChunk>, session: EmbeddingSession
): List<ChunkTokenCounts> {
    fun report(message: String) { Log.i("TP1ChunkTokens", message); println(message) }
    val source = markdown.toByteArray(Charsets.UTF_8)
    val counts = mutableListOf<ChunkTokenCounts>()
    val limit = session.maxInputTokens
    val sideTarget = minOf(16, limit / 16)
    val coreTarget = minOf(224, limit - 2 * sideTarget)
    val target = coreTarget + 2 * sideTarget
    report("$label: coreTarget=$coreTarget sideTarget=$sideTarget; exact counts include Document: and EOS; growth adds left then right")
    chunks.forEachIndexed { index, chunk ->
        val core = session.countDocumentTokens(String(source.copyOfRange(chunk.coreStartByte, chunk.coreEndByte), Charsets.UTF_8))
        val withLeft = session.countDocumentTokens(String(source.copyOfRange(chunk.startByte, chunk.coreEndByte), Charsets.UTF_8))
        val actual = session.countDocumentTokens(chunk.text)
        assertEquals(actual, chunk.inputTokens)
        val values = ChunkTokenCounts(core, withLeft - core, actual - withLeft)
        counts += values
        val characters = chunk.text.codePointCount(0, chunk.text.length)
        report(String.format(Locale.ROOT,
            "%s chunk=%d coreChars=%d finalChars=%d estimatedTextTokens=%.1f coreTokens=%d leftAdded=%d rightAdded=%d exactInputTokens=%d extraVsTarget=%d remaining=%d",
            label, index, chunk.coreCharacters, characters, characters / 3.5, values.core, values.left, values.right,
            actual, actual - target, limit - actual))
    }
    require(chunks.isNotEmpty())
    report(String.format(Locale.ROOT, "%s summary chunks=%d exactTokens min=%d average=%.1f max=%d", label, chunks.size,
        chunks.minOf { it.inputTokens }, chunks.map { it.inputTokens }.average(), chunks.maxOf { it.inputTokens }))
    report(String.format(Locale.ROOT, "%s cores min=%d average=%.1f max=%d; groupsAboveTarget=%d; contextGrowth=%d", label,
        counts.minOf { it.core }, counts.map { it.core }.average(), counts.maxOf { it.core }, chunks.count { it.inputTokens > target },
        counts.sumOf { it.left + it.right }))
    return counts
}

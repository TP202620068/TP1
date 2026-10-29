package com.example.tp1.extraction

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MarkdownChunkerTest {
    // Character counts only exercise splitting. Real Jina counts are reported by device tests.
    private fun chunks(text: String, limit: Int = 1024): List<MarkdownChunk> = runBlocking {
        chunkMarkdown(text, limit) { it.codePointCount(0, it.length) + 3 }
    }

    private fun assertCoverage(text: String, chunks: List<MarkdownChunk>) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val covered = BooleanArray(bytes.size)
        var previousCoreEnd = 0
        for (chunk in chunks) {
            assertArrayEquals(chunk.text.toByteArray(Charsets.UTF_8), bytes.copyOfRange(chunk.startByte, chunk.endByte))
            assertTrue(chunk.startByte <= chunk.coreStartByte)
            assertTrue(chunk.endByte >= chunk.coreEndByte)
            assertTrue(chunk.coreEndByte > chunk.coreStartByte)
            assertTrue(chunk.coreStartByte >= previousCoreEnd)
            val core = String(bytes.copyOfRange(chunk.coreStartByte, chunk.coreEndByte), Charsets.UTF_8)
            assertEquals(core.codePointCount(0, core.length), chunk.coreCharacters)
            previousCoreEnd = chunk.coreEndByte
            for (index in chunk.startByte until chunk.endByte) covered[index] = true
            assertTrue(chunk.text.isNotBlank())
            assertEquals(chunk.text.codePointCount(0, chunk.text.length) + 3, chunk.inputTokens)
        }
        val uncovered = bytes.indices.filter { !covered[it] }.map { bytes[it] }.toByteArray()
        assertTrue("Non-whitespace source content was lost", String(uncovered, Charsets.UTF_8).isBlank())
    }

    @Test fun emptyAndWhitespaceDocumentsDoNotCallTokenizer() = runBlocking<Unit> {
        for (text in listOf("", " \n\t\r\n")) {
            assertTrue(chunkMarkdown(text, 1024) { error("Blank text reached tokenizer") }.isEmpty())
        }
    }

    @Test fun coresAdvanceIndependentlyOfSentenceOverlap() {
        val text = ("A".repeat(99) + ". ").repeat(25)
        // A permissive fake budget isolates the character-first boundary policy.
        val result = chunks(text, 8192)
        assertEquals(listOf(784, 784, 784, 173), result.map { it.coreCharacters })
        assertEquals(0, result.first().startByte)
        assertEquals(909, result.first().endByte)
        assertEquals(707, result[1].startByte)
        assertEquals(784, result[1].coreStartByte)
        assertTrue(result.zipWithNext().all { (a, b) -> a.endByte > b.startByte })
        assertCoverage(text, result)
        assertEquals(result, chunks(text, 8192))
    }

    @Test fun punctuationFreeTextHasBoundedExpansionAndCompleteCoverage() {
        val text = "x".repeat(12_000)
        val result = chunks(text, 8192)
        assertEquals(16, result.size)
        assertTrue(result.all { it.text.length <= 784 + 2 * (56 + 784) })
        assertCoverage(text, result)
    }

    @Test fun headingsSeparateSectionsButFencedHeadingsDoNot() {
        val first = "# Primero\n" + "Garantía de dos años. ".repeat(100) + "\n"
        val second = "# Segundo\n" + "Factura pagada. ".repeat(100)
        val result = chunks(first + second)
        assertTrue(result.none { it.startByte < first.toByteArray().size && it.endByte > first.toByteArray().size })
        assertCoverage(first + second, result)

        val code = "# Código\n```markdown\n# Esto no es otra sección\nvalor = 42\n```\nFin.\n"
        assertEquals(1, chunks(code).size)
        assertCoverage(code, chunks(code))
    }

    @Test fun listsAndTablesPreferWholeLineBoundaries() {
        val text = "# Lista\n" + ("- " + "a".repeat(95) + "\n").repeat(20) +
            "\n# Tabla\n| Producto | Precio |\n| --- | --- |\n" + ("| " + "b".repeat(90) + " | 20 |\n").repeat(20)
        val result = chunks(text, 8192)
        for (chunk in result) {
            assertTrue(chunk.startByte == 0 || text[chunk.startByte - 1] == '\n')
            assertTrue(chunk.endByte == text.length || text[chunk.endByte - 1] == '\n')
        }
        assertCoverage(text, result)
    }

    @Test fun unicodeCodePointsAndUtf8OffsetsSurviveForcedSplitting() {
        val text = ("😀 日本語 garantía e\u0301.\n\n").repeat(150)
        val result = chunks(text, 64)
        assertTrue(result.all { it.inputTokens <= 64 })
        assertTrue(result.all { !it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate() })
        assertCoverage(text, result)
    }

    @Test fun overBudgetCoresAreSplitWithoutLosingText() {
        val text = "  " + "Identificador1234567890".repeat(100) + "\n\n" + " ".repeat(100) + "Fin."
        val result = chunks(text, 32)
        assertTrue(result.all { it.inputTokens <= 32 })
        assertCoverage(text, result)
    }

    @Test fun impossibleBudgetAndInvalidUnicodeFailExplicitly() {
        assertThrows(IllegalArgumentException::class.java) { chunks("😀", 3) }
        for (text in listOf("bad\u0000text", "bad\uD800", "bad\uDC00")) {
            assertThrows(IllegalArgumentException::class.java) { chunks(text) }
        }
        assertThrows(IllegalArgumentException::class.java) { chunks("Valid", 0) }
    }

    @Test fun tokenizerFailureAndCancellationAreNotSwallowed() {
        val failure = IllegalStateException("Tokenizer failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            runBlocking { chunkMarkdown("Text", 1024) { throw failure } }
        }
        assertSame(failure, actual)
        assertThrows(CancellationException::class.java) {
            runBlocking { chunkMarkdown("Text", 1024) { throw CancellationException("Stopped") } }
        }
    }

    @Test fun cancelledPreprocessingDoesNotReturnEvenForBlankInput() = runBlocking<Unit> {
        for (text in listOf("", " ".repeat(4096), "A valid document.")) {
            var returned = false
            val pending = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                chunkMarkdown(text, 1024) { error("Cancelled preprocessing reached token counting") }
                returned = true
            }
            pending.join()
            assertFalse("Cancelled chunking returned a result", returned)
        }
    }
}

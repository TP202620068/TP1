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
    // Synthetic counters exercise grouping and splitting. Real Jina counts are reported by device tests.
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

    @Test fun blankOnlyUnitsDoNotAccumulateAtCoreStarts() {
        val content = "Texto " + "界😀".repeat(120) + ".\n"
        for (padding in listOf("\n".repeat(4096), "\n".repeat(60_000), " \t\r\n".repeat(1000))) {
            val text = padding + content + padding
            val result = chunks(text, 8192)
            val chunk = result.single()
            assertEquals(padding.toByteArray(Charsets.UTF_8).size, chunk.coreStartByte)
            assertEquals((padding + content).toByteArray(Charsets.UTF_8).size, chunk.coreEndByte)
            assertEquals(content.codePointCount(0, content.length), chunk.coreCharacters)
            assertCoverage(text, result)
        }
    }

    @Test fun coresAdvanceIndependentlyOfSentenceOverlap() {
        val text = ("A".repeat(99) + ". ").repeat(25)
        val result = chunks(text)
        assertEquals(listOf(303, 303, 303, 303, 303, 303, 303, 303, 101), result.map { it.coreCharacters })
        assertEquals(0, result.first().startByte)
        assertEquals(404, result.first().endByte)
        assertEquals(202, result[1].startByte)
        assertEquals(303, result[1].coreStartByte)
        assertEquals(707, result[1].endByte)
        assertTrue(result.zipWithNext().all { (a, b) -> a.endByte > b.startByte })
        assertCoverage(text, result)
        assertEquals(result, chunks(text))
    }

    @Test fun punctuationFreeTextSplitsWithoutExceedingTheHardLimit() {
        val text = "x".repeat(12_000)
        val result = chunks(text)
        assertTrue(result.size > 1)
        assertTrue(result.all { it.inputTokens <= 1024 })
        assertCoverage(text, result)
    }

    @Test fun coreThresholdAndSymmetricContextUseCombinedCountsRatherThanSums() = runBlocking<Unit> {
        val unit = "A".repeat(45) + ". "
        val text = unit.repeat(20)
        val calls = mutableMapOf<String, Int>()
        // Independent units each pay 180 tokens, while joining units pays that overhead only once.
        val count: suspend (String) -> Int = { value ->
            calls[value] = (calls[value] ?: 0) + 1
            value.length + 180
        }
        val firstText = unit.replace('A', 'B') + unit.replace('A', 'C') + unit.replace('A', 'D')
        val first = chunkMarkdown(firstText, 1024, count).first()
        assertEquals(47, first.coreCharacters)
        assertEquals(227, firstText.substring(0, 47).length + 180)
        assertEquals(274, first.inputTokens)
        assertEquals(1, calls[firstText.substring(0, 47)])
        assertEquals(2, calls[firstText.substring(0, 94)])

        val result = chunkMarkdown(text, 1024) { it.length + 180 }
        assertEquals(47, result[1].coreCharacters)
        assertEquals(0, result[1].startByte)
        assertEquals(47, result[1].coreStartByte)
        assertEquals(94, result[1].coreEndByte)
        assertEquals(141, result[1].endByte)
        assertEquals(321, result[1].inputTokens)

        // Summing standalone counts would stop too soon; the complete core reaches 224 only after three units.
        val grouped = chunkMarkdown(text, 1024) { it.length + 100 }
        assertEquals(141, grouped.first().coreCharacters)
        assertEquals(241, grouped.first().coreCharacters + 100)
    }

    @Test fun unitThatWouldCrossHardLimitIsDeferredAndContextMayBeReduced() {
        val first = "A".repeat(99) + ". "
        val large = "B".repeat(949) + ". "
        val text = first + large + "Fin."
        val result = chunks(text)
        assertEquals(101, result.first().coreCharacters)
        assertEquals(101, result.first().endByte)
        assertTrue(result.all { it.inputTokens <= 1024 })
        assertCoverage(text, result)
    }

    @Test fun hugeLogicalUnitsRespectUtf8ByteLimitBeforeCallingTokenizer() = runBlocking<Unit> {
        val text = "界".repeat(30_000)
        val result = chunkMarkdown(text, 1024) { candidate ->
            assertTrue(candidate.toByteArray().size + 10 <= 65536)
            candidate.codePointCount(0, candidate.length) + 3
        }
        assertTrue(result.size > 1)
        assertCoverage(text, result)
    }

    @Test fun headingsRemainAttachedToTheirFirstContentUnit() {
        val text = "# Title\n\n" + "A".repeat(700) + ".\n"
        val result = chunks(text)
        assertEquals(1, result.size)
        assertEquals(text, result.single().text)
    }

    @Test fun nonMonotonicPrefixCountsStillProduceBoundedCompleteCoverage() = runBlocking<Unit> {
        val text = "a".repeat(2500)
        val result = chunkMarkdown(text, 64) { candidate ->
            candidate.length + 3 + if (candidate.length % 7 == 0) 12 else 0
        }
        assertTrue(result.all { it.inputTokens <= 64 })
        val source = text.toByteArray()
        val cores = result.flatMap { source.copyOfRange(it.coreStartByte, it.coreEndByte).toList() }.toByteArray()
        assertArrayEquals(source, cores)
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

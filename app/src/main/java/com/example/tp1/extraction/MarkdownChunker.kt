package com.example.tp1.extraction

import com.example.tp1.inference.EMBEDDING_DOCUMENT_PREFIX
import com.example.tp1.inference.EMBEDDING_INPUT_BYTES
import java.text.BreakIterator
import java.util.Locale
import java.util.TreeSet
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Half-open UTF-8 byte ranges refer to the unchanged canonical Markdown, not the Document: prefix. */
internal data class MarkdownChunk(
    val text: String,
    val startByte: Int,
    val endByte: Int,
    val coreStartByte: Int,
    val coreEndByte: Int,
    val coreCharacters: Int,
    val inputTokens: Int
)

private const val CORE_TOKENS = 224
private const val CONTEXT_TOKENS = 16
private const val MAX_TEXT_BYTES = EMBEDDING_INPUT_BYTES - EMBEDDING_DOCUMENT_PREFIX.length

/** Logical units form a 224+ token core, with 16+ token growth on each side. */
internal suspend fun chunkMarkdown(
    markdown: String,
    maxInputTokens: Int,
    countTokens: suspend (String) -> Int
): List<MarkdownChunk> {
    require(maxInputTokens in 1..8192)
    val coroutine = currentCoroutineContext()
    coroutine.ensureActive()
    markdown.forEachIndexed { index, char ->
        if (index % 1024 == 0) coroutine.ensureActive()
        require(char != '\u0000' &&
            (!char.isHighSurrogate() || index + 1 < markdown.length && markdown[index + 1].isLowSurrogate()) &&
            (!char.isLowSurrogate() || index > 0 && markdown[index - 1].isHighSurrogate())) { "Markdown must be valid Unicode without NUL" }
    }
    if (markdown.isBlank()) return emptyList()
    val (boundaries, sections) = markdownBoundaries(markdown, coroutine)
    val result = mutableListOf<MarkdownChunk>()
    val contextTarget = minOf(CONTEXT_TOKENS, maxInputTokens / 16)
    val coreTarget = minOf(CORE_TOKENS, maxInputTokens - 2 * contextTarget)
    // Cache exact ranges, not sums of independently prefixed units. Bound memory to the current chunk.
    val counts = mutableMapOf<Pair<Int, Int>, Int>()
    suspend fun count(start: Int, end: Int): Int {
        coroutine.ensureActive()
        val key = start to end
        counts[key]?.let { return it }
        // This sentinel means byte-overflow, not an estimated token count. Never emit it.
        if (end - start > MAX_TEXT_BYTES) return Int.MAX_VALUE
        val text = markdown.substring(start, end)
        val tokens = when {
            text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES -> Int.MAX_VALUE
            text.isBlank() -> 0
            else -> countTokens(text).also { require(it > 0) { "Tokenizer returned no tokens" } }
        }
        counts[key] = tokens
        return tokens
    }

    suspend fun splitEnd(start: Int, end: Int): Int {
        var low = nextCharacter(markdown, start)
        var high = minOf(end, start + MAX_TEXT_BYTES)
        if (high < markdown.length && markdown[high].isLowSurrogate()) high--
        var below = start
        var above = start
        // Prefix counts need not be monotonic. Search proposes a cut; exact checks still govern every output.
        while (low <= high) {
            coroutine.ensureActive()
            var middle = low + (high - low) / 2
            if (middle < markdown.length && markdown[middle].isLowSurrogate()) middle--
            val tokens = count(start, middle)
            if (tokens > maxInputTokens || tokens >= coreTarget) {
                if (tokens <= maxInputTokens) above = middle
                high = previousCharacter(markdown, middle)
            } else {
                below = middle
                low = if (middle == end) end + 1 else nextCharacter(markdown, middle)
            }
        }
        val cut = if (above > start) above else below
        require(cut > start) { "Embedding budget cannot fit one character with prefix and EOS" }
        // Prefer finishing the current word, without scanning an unbounded punctuation-free document.
        var wordEnd = cut
        val searchEnd = minOf(end, start + MAX_TEXT_BYTES)
        while (wordEnd < searchEnd && !markdown[wordEnd].isWhitespace()) {
            if ((wordEnd - cut) % 1024 == 0) coroutine.ensureActive()
            wordEnd = nextCharacter(markdown, wordEnd)
        }
        return if (wordEnd <= end && wordEnd > cut && wordEnd <= searchEnd &&
            (wordEnd == end || markdown[wordEnd].isWhitespace()) && count(start, wordEnd) <= maxInputTokens) wordEnd else cut
    }
    var byteCursor = 0
    var characterCursor = 0
    fun byteOffset(index: Int): Int {
        if (index >= characterCursor) byteCursor += markdown.substring(characterCursor, index).toByteArray(Charsets.UTF_8).size
        else byteCursor -= markdown.substring(index, characterCursor).toByteArray(Charsets.UTF_8).size
        characterCursor = index
        return byteCursor
    }

    for (section in sections.zipWithNext()) {
        val (sectionStart, sectionEnd) = section
        var coreStart = sectionStart
        while (coreStart < sectionEnd) {
            coroutine.ensureActive()
            counts.clear()
            var coreEnd = coreStart
            var coreTokens = 0
            while (coreEnd < sectionEnd && coreTokens < coreTarget) {
                coroutine.ensureActive()
                val next = minOf(checkNotNull(boundaries.higher(coreEnd)), sectionEnd)
                val tokens = count(coreStart, next)
                if (tokens > maxInputTokens) {
                    if (coreTokens == 0 || count(coreEnd, next) > maxInputTokens) {
                        coreEnd = splitEnd(coreStart, next)
                        boundaries.add(coreEnd)
                        coreTokens = count(coreStart, coreEnd)
                    }
                    break
                }
                coreEnd = next
                coreTokens = tokens
                // Avoid repeatedly copying a growing blank prefix.
                if (coreTokens == 0) coreStart = coreEnd
            }
            if (coreTokens == 0) { coreStart = coreEnd; continue }
            check(coreEnd > coreStart) { "Chunking made no progress" }
            var start = coreStart
            var end = coreEnd
            var tokens = coreTokens
            while (start > sectionStart && tokens - coreTokens < contextTarget) {
                val previous = maxOf(checkNotNull(boundaries.lower(start)), sectionStart)
                val expanded = count(previous, coreEnd)
                if (expanded > maxInputTokens) break
                start = previous
                tokens = expanded
            }
            val withLeft = tokens
            while (end < sectionEnd && tokens - withLeft < contextTarget) {
                val next = minOf(checkNotNull(boundaries.higher(end)), sectionEnd)
                val expanded = count(start, next)
                if (expanded > maxInputTokens) break
                end = next
                tokens = expanded
            }
            check(tokens in 1..maxInputTokens)
            result += MarkdownChunk(markdown.substring(start, end), byteOffset(start), byteOffset(end),
                byteOffset(coreStart), byteOffset(coreEnd), markdown.codePointCount(coreStart, coreEnd), tokens)
            coreStart = coreEnd
        }
    }
    coroutine.ensureActive()
    return result
}

private fun nextCharacter(text: String, index: Int): Int = index + Character.charCount(text.codePointAt(index))
private fun previousCharacter(text: String, index: Int): Int = index - Character.charCount(text.codePointBefore(index))

private val heading = Regex("^ {0,3}#{1,6}(?:\\s|$)")
private val listItem = Regex("^\\s*(?:[-+*]|[0-9]+[.)])\\s+")
private val fence = Regex("^ {0,3}(`{3,}|~{3,})")

private fun markdownBoundaries(text: String, coroutine: CoroutineContext): Pair<TreeSet<Int>, List<Int>> {
    val boundaries = TreeSet<Int>().apply { add(0); add(text.length) }
    val sections = mutableListOf(0)
    val sentences = BreakIterator.getSentenceInstance(Locale.forLanguageTag("es"))
    var proseStart = -1
    var attachHeading = false
    fun finishProse(end: Int) {
        if (proseStart < 0) return
        if (!attachHeading) boundaries.add(proseStart)
        sentences.setText(text.substring(proseStart, end))
        var boundary = sentences.first()
        while (boundary != BreakIterator.DONE) {
            coroutine.ensureActive()
            if (!attachHeading || boundary != 0) boundaries.add(proseStart + boundary)
            boundary = sentences.next()
        }
        proseStart = -1
        attachHeading = false
    }
    var fenceCharacter: Char? = null
    var fenceLength = 0
    var lineStart = 0
    // shortcut: recognizes AnyDoc's common block forms, not full CommonMark; extend when a real fixture requires it.
    while (lineStart < text.length) {
        coroutine.ensureActive()
        val newline = text.indexOf('\n', lineStart)
        val end = if (newline < 0) text.length else newline + 1
        val line = text.substring(lineStart, end).trimEnd('\r', '\n')
        val marker = fence.find(line)?.groupValues?.get(1)
        if (fenceCharacter != null) {
            if (marker != null && marker.first() == fenceCharacter && marker.length >= fenceLength &&
                line.trim().all { it == fenceCharacter }) {
                fenceCharacter = null
                boundaries.add(end)
            }
        } else if (marker != null) {
            finishProse(lineStart)
            if (!attachHeading) boundaries.add(lineStart)
            attachHeading = false
            fenceCharacter = marker.first()
            fenceLength = marker.length
        } else if (heading.containsMatchIn(line)) {
            finishProse(lineStart)
            if (lineStart != 0) sections.add(lineStart)
            boundaries.add(lineStart)
            attachHeading = true
        } else if (line.isBlank()) {
            finishProse(lineStart)
            if (!attachHeading) { boundaries.add(lineStart); boundaries.add(end) }
        } else if (listItem.containsMatchIn(line) || line.trimStart().startsWith("|")) {
            finishProse(lineStart)
            if (!attachHeading) boundaries.add(lineStart)
            boundaries.add(end)
            attachHeading = false
        } else if (proseStart < 0) proseStart = lineStart
        lineStart = end
    }
    finishProse(text.length)
    sections.add(text.length)
    return boundaries to sections
}

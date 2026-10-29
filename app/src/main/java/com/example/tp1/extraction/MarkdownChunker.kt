package com.example.tp1.extraction

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

private const val CORE_CHARACTERS = 784 // (256 - 32) * 3.5
private const val CONTEXT_CHARACTERS = 56 // 16 * 3.5 on each side

/** Character-first v1. The counter must include the retrieval prefix and EOS, without truncation. */
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
            var coreEnd = moveCharacters(markdown, coreStart, CORE_CHARACTERS, sectionEnd)
            val before = moveCharacters(markdown, coreStart, -CONTEXT_CHARACTERS, sectionStart)
            val after = moveCharacters(markdown, coreEnd, CONTEXT_CHARACTERS, sectionEnd)
            var start = snapBoundary(markdown, boundaries, before, sectionStart, backwards = true)
            var end = snapBoundary(markdown, boundaries, after, sectionEnd, backwards = false)
            var text = markdown.substring(start, end)
            if (text.isBlank()) { coreStart = coreEnd; continue }
            var tokens = countTokens(text)
            require(tokens > 0) { "Tokenizer returned no tokens" }
            while (tokens > maxInputTokens) {
                coroutine.ensureActive()
                if (start != coreStart || end != coreEnd) {
                    // Drop optional overlap before splitting the core. No original content is discarded.
                    start = coreStart
                    end = coreEnd
                } else {
                    val characters = markdown.codePointCount(start, end)
                    require(characters > 1) { "Embedding budget cannot fit one character with prefix and EOS" }
                    val middle = moveCharacters(markdown, start, characters / 2, end)
                    val boundary = boundaries.floor(middle)
                    end = if (boundary != null && boundary > start) boundary
                        else snapBoundary(markdown, boundaries, middle, start, backwards = true).coerceAtLeast(
                            moveCharacters(markdown, start, 1, end))
                }
                coreEnd = end
                text = markdown.substring(start, end)
                if (text.isBlank()) break
                tokens = countTokens(text)
                require(tokens > 0) { "Tokenizer returned no tokens" }
            }
            if (text.isNotBlank()) {
                result += MarkdownChunk(text, byteOffset(start), byteOffset(end), byteOffset(coreStart), byteOffset(coreEnd),
                    markdown.codePointCount(coreStart, coreEnd), tokens)
            }
            coreStart = coreEnd
        }
    }
    coroutine.ensureActive()
    return result
}

/** Count Unicode code points, never UTF-16 halves. Work is bounded by the requested distance. */
private fun moveCharacters(text: String, from: Int, distance: Int, limit: Int): Int {
    var index = from
    repeat(kotlin.math.abs(distance)) {
        if (distance < 0) {
            if (index <= limit) return index
            index -= Character.charCount(text.codePointBefore(index))
        } else {
            if (index >= limit) return index
            index += Character.charCount(text.codePointAt(index))
        }
    }
    return index
}

private fun snapBoundary(text: String, boundaries: TreeSet<Int>, at: Int, limit: Int, backwards: Boolean): Int {
    // Bound sentence completion to one core width; a punctuation-free paragraph must not consume the document.
    val searchEnd = moveCharacters(text, at, if (backwards) -CORE_CHARACTERS else CORE_CHARACTERS, limit)
    val boundary = if (backwards) boundaries.floor(at) else boundaries.ceiling(at)
    if (boundary != null && if (backwards) boundary >= searchEnd else boundary <= searchEnd) return boundary
    var index = at
    while (index != searchEnd) {
        if (index == text.length || text[index].isWhitespace()) return index
        index = moveCharacters(text, index, if (backwards) -1 else 1, searchEnd)
    }
    return at
}

private val heading = Regex("^ {0,3}#{1,6}(?:\\s|$)")
private val listItem = Regex("^\\s*(?:[-+*]|[0-9]+[.)])\\s+")
private val fence = Regex("^ {0,3}(`{3,}|~{3,})")

private fun markdownBoundaries(text: String, coroutine: CoroutineContext): Pair<TreeSet<Int>, List<Int>> {
    val boundaries = TreeSet<Int>().apply { add(0); add(text.length) }
    val sections = mutableListOf(0)
    val sentences = BreakIterator.getSentenceInstance(Locale.forLanguageTag("es"))
    var proseStart = -1
    fun finishProse(end: Int) {
        if (proseStart < 0) return
        boundaries.add(proseStart)
        sentences.setText(text.substring(proseStart, end))
        var boundary = sentences.first()
        while (boundary != BreakIterator.DONE) {
            coroutine.ensureActive()
            boundaries.add(proseStart + boundary)
            boundary = sentences.next()
        }
        proseStart = -1
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
            boundaries.add(lineStart)
            fenceCharacter = marker.first()
            fenceLength = marker.length
        } else if (heading.containsMatchIn(line)) {
            finishProse(lineStart)
            if (lineStart != 0) sections.add(lineStart)
            boundaries.add(lineStart)
            boundaries.add(end)
        } else if (line.isBlank() || listItem.containsMatchIn(line) || line.trimStart().startsWith("|")) {
            finishProse(lineStart)
            boundaries.add(lineStart)
            boundaries.add(end)
        } else if (proseStart < 0) proseStart = lineStart
        lineStart = end
    }
    finishProse(text.length)
    sections.add(text.length)
    return boundaries to sections
}

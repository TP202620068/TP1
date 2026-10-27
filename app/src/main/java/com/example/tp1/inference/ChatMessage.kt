package com.example.tp1.inference

import java.nio.CharBuffer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ChatRole { User, Assistant }
internal data class ChatMessage(val role: ChatRole, val text: String)

internal fun chatBytes(text: String): ByteArray {
    require('\u0000' !in text) { "Chat messages cannot contain NUL" }
    require(text.length <= 64 * 1024) { "Request exceeds byte limit" }
    val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(text))
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

internal fun validChatInput(text: String): Boolean = text.isNotBlank() &&
    runCatching { chatBytes(text).size <= 64 * 1024 }.getOrDefault(false)

/** Keeps whole recent turns. The tokenizer counts the complete template, including BOS and roles. */
internal suspend fun budgetChatMessages(
    system: String,
    history: List<ChatMessage>,
    input: String,
    countTokens: suspend (List<ChatMessage>) -> Int
): List<ChatMessage> {
    require(input.isNotBlank()) { "Empty chat input" }
    require(history.size % 2 == 0 && history.withIndex().all { (index, message) ->
        message.role == if (index % 2 == 0) ChatRole.User else ChatRole.Assistant
    }) { "History must contain complete user/assistant pairs" }
    val fixedBytes = chatBytes(system).size + chatBytes(input).size
    require(fixedBytes <= 64 * 1024) { "Request exceeds 64 KiB" }
    val sizes = history.map { chatBytes(it.text).size }
    var bytes = fixedBytes.toLong() + sizes.sumOf { it.toLong() }
    var first = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val messages = history.drop(first) + ChatMessage(ChatRole.User, input)
        if (bytes <= 64 * 1024 && messages.size <= 1023) {
            val tokens = countTokens(messages)
            // Keep both checks: the output reservation may increase independently of the input ceiling.
            if (tokens <= 3584 && tokens + 256 < 4096) return messages
        }
        require(first < history.size) { "Latest input exceeds the context budget" }
        bytes -= sizes[first] + sizes[first + 1]
        first += 2
    }
}

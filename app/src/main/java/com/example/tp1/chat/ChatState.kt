package com.example.tp1.chat

import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.ChatRole
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.chatBytes
import com.example.tp1.inference.validChatInput

internal const val CHAT_SYSTEM_PROMPT = "You are a helpful assistant. Answer the user's questions clearly and briefly, " +
    "in the language they use. You do not have access to their files in this version; do not claim otherwise."

internal enum class ChatPreparation { Unprepared, Acquiring, Loading, Ready, Closing, Error }
internal enum class ChatPhase { Idle, Validating, Generating, Stopping }
internal enum class ChatProblem { Preparation, InvalidInput, ContextLimit, Generation }

internal data class ChatTurn(
    val id: Long,
    val input: String,
    val answer: String = "",
    val running: Boolean = false,
    val finish: FinishReason? = null,
    val failed: Boolean = false
) {
    val canRetry: Boolean get() = !running && (failed || finish in setOf(
        FinishReason.CANCELLED, FinishReason.DEADLINE, FinishReason.NATIVE_FAILURE))
    // LENGTH answers are deliberately replayed as displayed. Failed/stopped partials never are.
    val reusable: Boolean get() = !failed && !running && finish in setOf(FinishReason.EOG, FinishReason.LENGTH) &&
        validChatInput(answer)
}

internal data class ChatState(
    val draft: String = "",
    val turns: List<ChatTurn> = emptyList(),
    val preparation: ChatPreparation = ChatPreparation.Unprepared,
    val phase: ChatPhase = ChatPhase.Idle,
    val problem: ChatProblem? = null
) {
    val busy: Boolean get() = phase != ChatPhase.Idle || preparation in setOf(
        ChatPreparation.Acquiring, ChatPreparation.Loading, ChatPreparation.Closing)
    val validDraft: Boolean get() = validChatInput(draft) &&
        chatBytes(draft).size + CHAT_SYSTEM_PROMPT.toByteArray().size <= 64 * 1024
    val canSend: Boolean get() = preparation == ChatPreparation.Ready && !busy && validDraft && problem != ChatProblem.InvalidInput
}

internal fun List<ChatTurn>.replayMessages(): List<ChatMessage> = filter { it.reusable }.flatMap {
    // Generated NUL is displayable, but cannot cross the formatter's C-string input boundary.
    listOf(ChatMessage(ChatRole.User, it.input), ChatMessage(ChatRole.Assistant, it.answer))
}

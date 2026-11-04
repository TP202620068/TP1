package com.example.tp1.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Main-thread Chat ownership, separate from the extractor/engine's loaded-memory permit. */
internal object NativeWorkHandoff {
    class ChatOwner(val busy: () -> Boolean, val yield: suspend () -> Unit, val resume: () -> Unit)

    val documents = Mutex()
    private val chats = mutableSetOf<ChatOwner>()
    private var owner: Any? = null
    val blocked: Boolean get() = owner != null

    fun register(chat: ChatOwner) { chats += chat }
    fun unregister(chat: ChatOwner) { chats -= chat }

    suspend fun acquire(ticket: Any): Boolean = withContext(Dispatchers.Main.immediate) {
        if (owner != null || chats.any { it.busy() }) return@withContext false
        owner = ticket
        // The caller releases by identity even if cancellation suppresses delivery of this result.
        for (chat in chats.toList()) chat.yield()
        true
    }

    suspend fun release(ticket: Any): Unit = withContext(NonCancellable + Dispatchers.Main.immediate) {
        if (owner === ticket) {
            owner = null
            chats.toList().forEach { it.resume() }
        }
    }
}

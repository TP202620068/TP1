package com.example.tp1.chat

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.GenerationEvent
import com.example.tp1.inference.InferenceSession
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import com.example.tp1.inference.NativeWorkHandoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

// Saved state stores UTF-16; keep its size bounded independently of the UTF-8 request budget.
private const val MAX_DRAFT_LENGTH = 64 * 1024
private const val BACKGROUND_TIMEOUT_MILLIS = 120_000L

/** Concrete function seam for deterministic owner/UI checks without loading weights. */
internal class ChatConnection(
    val prepare: suspend (String, List<ChatMessage>) -> List<ChatMessage>,
    val stream: (List<ChatMessage>) -> Flow<GenerationEvent>,
    val cancel: () -> Unit,
    val closeAsync: () -> Deferred<Unit>
)

internal class ChatViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val downloadModel: suspend () -> Unit,
    private val openModel: suspend () -> ChatConnection,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(application, savedState,
        downloadModel = { ModelManager(application).download(Model.Test) },
        openModel = {
            val session = InferenceSession.open(ModelManager(application), Model.Test, CHAT_SYSTEM_PROMPT)
            ChatConnection(session::prepare, session::stream, session::cancel, session::closeAsync)
        })

    private val mutableState = MutableStateFlow(run {
        val draft = savedState.get<String>("chatDraft").orEmpty()
        if (draft.length <= MAX_DRAFT_LENGTH) ChatState(draft = draft)
        else {
            savedState["chatDraft"] = ""
            ChatState(problem = ChatProblem.InvalidInput)
        }
    })
    val state = mutableState.asStateFlow()
    private var connection: ChatConnection? = null
    private var closing: Deferred<Unit>? = null
    private var releasing: Job? = null
    private var cleared = false
    private var operation: Job? = null
    private var unloadJob: Job? = null
    private var backgroundedAt: Long? = null
    private var epoch = 0L
    private var nextTurn = 0L
    private var foreground = false
    private var selected = false
    private val active: Boolean get() = foreground && selected
    private val nativeOwner = NativeWorkHandoff.ChatOwner(
        busy = { !cleared && state.value.phase != ChatPhase.Idle && state.value.preparation != ChatPreparation.Closing },
        yield = { yieldForIndexing() }, resume = { prepare() })

    init { NativeWorkHandoff.register(nativeOwner) }

    fun editDraft(value: String) {
        if (value.length > MAX_DRAFT_LENGTH) {
            mutableState.value = state.value.copy(problem = ChatProblem.InvalidInput)
            return
        }
        // A same-value IME update is not a correction of a rejected edit.
        if (value == state.value.draft) return
        savedState["chatDraft"] = value
        mutableState.value = state.value.copy(draft = value, problem = state.value.problem?.takeUnless {
            it == ChatProblem.InvalidInput || it == ChatProblem.ContextLimit
        })
    }

    fun setSelected(value: Boolean) {
        if (selected == value) return
        selected = value
        if (active) prepare()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        unloadJob?.cancel()
        unloadJob = null
        if (foreground) {
            // Elapsed time includes device sleep, unlike the scheduled delay.
            val expired = backgroundedAt?.let { elapsedRealtime() - it >= BACKGROUND_TIMEOUT_MILLIS } == true
            backgroundedAt = null
            if (expired) release()
            if (selected) prepare()
        } else {
            backgroundedAt = elapsedRealtime()
            stop()
            unloadJob = viewModelScope.launch {
                delay(BACKGROUND_TIMEOUT_MILLIS.milliseconds)
                release()
            }
        }
    }

    fun prepare() {
        if (cleared || NativeWorkHandoff.blocked || !active || state.value.busy || connection != null) return
        val ticket = ++epoch
        mutableState.value = state.value.copy(preparation = ChatPreparation.Acquiring, problem = keepDraftError(null))
        startOperation {
            var opened: ChatConnection? = null
            try {
                downloadModel()
                currentCoroutineContext().ensureActive()
                if (ticket != epoch) throw CancellationException()
                mutableState.value = state.value.copy(preparation = ChatPreparation.Loading)
                opened = openModel()
                currentCoroutineContext().ensureActive()
                if (ticket != epoch) throw CancellationException()
                connection = opened
                opened = null
                mutableState.value = state.value.copy(preparation = ChatPreparation.Ready)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("TP1Chat", "Chat preparation failed", error)
                if (ticket == epoch) mutableState.value = state.value.copy(
                    preparation = ChatPreparation.Error, problem = keepDraftError(ChatProblem.Preparation))
            } finally {
                withContext(NonCancellable) { opened?.closeAsync()?.await() }
            }
        }
    }

    fun send() {
        if (NativeWorkHandoff.blocked || !active || !state.value.canSend) return
        generate(state.value.draft, retry = false)
    }

    fun retry() {
        if (NativeWorkHandoff.blocked || !active || state.value.busy || connection == null) return
        val turn = state.value.turns.lastOrNull()?.takeIf { it.canRetry } ?: return
        generate(turn.input, retry = true)
    }

    private fun generate(text: String, retry: Boolean) {
        val owner = connection ?: return
        val input = text.trim()
        val ticket = ++epoch
        val history = (if (retry) state.value.turns.dropLast(1) else state.value.turns).replayMessages()
        var accepted: Long? = null
        mutableState.value = state.value.copy(phase = ChatPhase.Validating, problem = keepDraftError(null))
        startOperation {
            try {
                val messages = owner.prepare(input, history)
                currentCoroutineContext().ensureActive()
                if (ticket != epoch || !foreground) throw CancellationException()
                val turn = if (retry) state.value.turns.last().copy(answer = "", running = true, finish = null, failed = false)
                    else ChatTurn(++nextTurn, input, running = true)
                accepted = turn.id
                if (!retry && state.value.draft == text) editDraft("")
                mutableState.value = state.value.copy(
                    turns = (if (retry) state.value.turns.dropLast(1) else state.value.turns) + turn,
                    phase = ChatPhase.Generating)
                var finished = false
                owner.stream(messages).collect { event ->
                    currentCoroutineContext().ensureActive()
                    if (ticket != epoch || !foreground) throw CancellationException()
                    check(!finished) { "Event after terminal status" }
                    when (event) {
                        is GenerationEvent.Started -> Unit
                        is GenerationEvent.Text -> updateTurn(turn.id) { it.copy(answer = it.answer + event.value) }
                        is GenerationEvent.Finished -> {
                            finished = true
                            if (event.detail != null) Log.w("TP1Chat", "Inference: ${event.detail}")
                            updateTurn(turn.id) { it.copy(running = false, finish = event.reason,
                                failed = event.reason == FinishReason.NATIVE_FAILURE ||
                                    (event.reason == FinishReason.EOG && it.answer.isBlank())) }
                        }
                    }
                }
                check(finished) { "Stream ended without terminal status" }
            } catch (error: CancellationException) {
                if (ticket == epoch) accepted?.let { id -> updateTurn(id) {
                    if (it.running) it.copy(running = false, finish = FinishReason.CANCELLED) else it
                } }
                throw error
            } catch (error: Exception) {
                Log.w("TP1Chat", "Chat request failed", error)
                if (ticket == epoch) {
                    if (accepted == null) mutableState.value = state.value.copy(problem = keepDraftError(
                        if (error is IllegalArgumentException) ChatProblem.ContextLimit else ChatProblem.Generation))
                    else updateTurn(accepted) { it.copy(running = false, failed = true, finish = FinishReason.NATIVE_FAILURE) }
                }
            } finally {
                if (ticket == epoch) mutableState.value = state.value.copy(phase = ChatPhase.Idle)
            }
        }
    }

    fun stop() {
        if (state.value.phase !in setOf(ChatPhase.Validating, ChatPhase.Generating)) return
        mutableState.value = state.value.copy(phase = ChatPhase.Stopping)
        connection?.cancel()
        operation?.cancel()
    }

    private fun updateTurn(id: Long, change: (ChatTurn) -> ChatTurn) {
        mutableState.value = state.value.copy(turns = state.value.turns.map { if (it.id == id) change(it) else it })
    }

    private fun keepDraftError(problem: ChatProblem?): ChatProblem? =
        state.value.problem?.takeIf { it == ChatProblem.InvalidInput } ?: problem

    private fun startOperation(block: suspend () -> Unit) {
        operation = viewModelScope.launch(start = CoroutineStart.LAZY) { block() }
        operation!!.start()
    }

    private fun release() {
        if (state.value.preparation == ChatPreparation.Closing) return
        if (connection == null && operation?.isActive != true) return
        val ticket = ++epoch
        val previous = operation
        releasing = previous
        val closed = connection?.closeAsync()
        closing = closed
        connection = null
        previous?.cancel()
        mutableState.value = state.value.copy(preparation = ChatPreparation.Closing,
            phase = ChatPhase.Stopping, turns = state.value.turns.map {
                if (it.running) it.copy(running = false, finish = FinishReason.CANCELLED) else it
            })
        startOperation {
            try {
                withContext(NonCancellable) {
                    previous?.join()
                    closed?.await()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("TP1Chat", "Chat cleanup failed", error)
            } finally {
                if (closing === closed && closed?.isCompleted == true) closing = null
                if (releasing === previous) releasing = null
                if (ticket == epoch) {
                    mutableState.value = state.value.copy(preparation = ChatPreparation.Unprepared, phase = ChatPhase.Idle)
                    // A foreground return can arrive before native cleanup finishes.
                    if (active) prepare()
                }
            }
        }
    }

    /** The handoff has already blocked prepare/send, and never yields an active reply. */
    private suspend fun yieldForIndexing() {
        val ticket = ++epoch
        val previous = operation
        val pendingRelease = releasing
        val closed = connection?.closeAsync() ?: closing
        connection = null
        closing = closed
        previous?.cancel()
        mutableState.value = state.value.copy(preparation = ChatPreparation.Closing, phase = ChatPhase.Idle)
        try {
            withContext(NonCancellable) {
                previous?.join()
                pendingRelease?.join()
                closed?.await()
            }
        } finally {
            if (closing === closed && closed?.isCompleted == true) closing = null
            if (ticket == epoch) {
                mutableState.value = state.value.copy(preparation = ChatPreparation.Unprepared, phase = ChatPhase.Idle)
            }
        }
    }

    override fun onCleared() {
        cleared = true
        ++epoch
        unloadJob?.cancel()
        val previous = operation
        val pendingRelease = releasing
        closing = connection?.closeAsync() ?: closing
        val closed = closing
        connection = null
        previous?.cancel()
        // Retiring owners still exclude indexing until late loads and native close have finished.
        viewModelScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                pendingRelease?.join()
                closed?.await()
            } catch (_: Exception) {
                Log.w("TP1Chat", "Retired Chat cleanup failed")
            } finally { NativeWorkHandoff.unregister(nativeOwner) }
        }
        super.onCleared()
    }
}

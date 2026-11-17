package com.example.tp1.chat

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.tp1.browser.DocumentReference
import com.example.tp1.browser.documentIntent
import com.example.tp1.R
import com.example.tp1.inference.ChatMessage
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.GenerationEvent
import com.example.tp1.inference.InferenceSession
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelAcquisition
import com.example.tp1.inference.ModelManager
import com.example.tp1.inference.NativeWorkHandoff
import com.example.tp1.indexing.DocumentRetriever
import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.indexing.RetrievalResult
import com.example.tp1.indexing.RAG_CHUNKS
import com.example.tp1.indexing.selectRagMatches
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
private const val BACKGROUND_TIMEOUT_MILLIS = 300_000L

/** Concrete function seam for deterministic owner/UI checks without loading weights. */
internal class ChatConnection(
    val prepare: suspend (String, List<ChatMessage>) -> List<ChatMessage>,
    val stream: (List<ChatMessage>) -> Flow<GenerationEvent>,
    val cancel: () -> Unit,
    val closeAsync: () -> Deferred<Unit>,
    val countPassageTokens: suspend (String) -> Int
)

internal class ChatViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val downloadModel: suspend () -> Unit,
    private val openModel: suspend () -> ChatConnection,
    private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() },
    private val retrieve: suspend (String) -> RetrievalResult = { DocumentRetriever(application).retrieve(it, k = RAG_CHUNKS) },
    private val checkContext: suspend (List<RetrievalMatch>) -> Unit = { DocumentRetriever(application).checkContext(it) }
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(application, savedState,
        downloadModel = { ModelAcquisition.get(application).acquire(Model.Test) },
        openModel = {
            val session = InferenceSession.open(ModelManager(application), Model.Test, CHAT_SYSTEM_PROMPT)
            ChatConnection(session::prepare, session::stream, session::cancel, session::closeAsync, session::countPassageTokens)
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
    private var submission: Job? = null
    private var retiringSubmission: Job? = null
    private var submissionEpoch = 0L
    private var unloadJob: Job? = null
    private var backgroundedAt: Long? = null
    private var epoch = 0L
    private var nextTurn = 0L
    private var foreground = false
    private var selected = false
    private val active: Boolean get() = foreground && selected
    private val nativeOwner = NativeWorkHandoff.ChatOwner(
        busy = { !cleared && state.value.phase !in setOf(ChatPhase.Idle, ChatPhase.Retrieving) &&
            state.value.preparation != ChatPreparation.Closing },
        yield = { yieldForIndexing() }, resume = { prepare() },
        retrieving = { !cleared && state.value.phase == ChatPhase.Retrieving })

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
            it == ChatProblem.InvalidInput || it == ChatProblem.ContextLimit || it == ChatProblem.QueryLimit
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
        if (cleared || NativeWorkHandoff.blocked || !active || state.value.busy || submission?.isActive == true || connection != null) return
        val ticket = ++epoch
        mutableState.value = state.value.copy(preparation = ChatPreparation.Acquiring,
            problem = state.value.problem?.takeUnless { it == ChatProblem.Preparation })
        startOperation {
            try {
                loadConnection(ticket)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("TP1Chat", "Chat preparation failed", error)
                if (ticket == epoch) mutableState.value = state.value.copy(
                    preparation = ChatPreparation.Error, problem = keepDraftError(ChatProblem.Preparation))
            }
        }
    }

    private suspend fun loadConnection(ticket: Long) {
        var opened: ChatConnection? = null
        try {
            mutableState.value = state.value.copy(preparation = ChatPreparation.Acquiring)
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
        } finally { withContext(NonCancellable) { opened?.closeAsync()?.await() } }
    }

    fun send() {
        if (cleared || !active || !state.value.canSend) return
        generate(state.value.draft, retry = false)
    }

    fun retry() {
        if (cleared || !active || state.value.busy) return
        val turn = state.value.turns.lastOrNull()?.takeIf { it.canRetry } ?: return
        generate(turn.input, retry = true)
    }

    private fun generate(text: String, retry: Boolean) {
        val input = text.trim()
        val ticket = ++submissionEpoch
        val history = (if (retry) state.value.turns.dropLast(1) else state.value.turns).replayMessages()
        var accepted: Long? = null
        var preflightProblem = ChatProblem.Retrieval
        mutableState.value = state.value.copy(phase = ChatPhase.Retrieving, problem = keepDraftError(null))
        submission = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = try { retrieve(input) }
                catch (error: IllegalArgumentException) { preflightProblem = ChatProblem.QueryLimit; throw error }
                if (result == RetrievalResult.Busy) {
                    preflightProblem = ChatProblem.RetrievalBusy
                    throw IllegalStateException("Retrieval busy")
                }
                currentCoroutineContext().ensureActive()
                if (ticket != submissionEpoch || !foreground) throw CancellationException()
                val selected = selectRagMatches((result as RetrievalResult.Complete).matches)
                if (selected.isEmpty()) {
                    insufficientEvidence(input, text, retry)
                    return@launch
                }
                mutableState.value = state.value.copy(phase = ChatPhase.Validating)
                // The submission reserves Chat now; a document handoff cannot close the newly opened owner.
                operation?.join()
                closing?.await()
                preflightProblem = ChatProblem.Preparation
                if (connection == null) loadConnection(++epoch)
                val owner = checkNotNull(connection)
                preflightProblem = ChatProblem.ContextLimit
                val request = prepareChatRequest(input, history, selected, owner.countPassageTokens, owner.prepare)
                currentCoroutineContext().ensureActive()
                if (ticket != submissionEpoch || !foreground) throw CancellationException()
                if (request.included.isEmpty()) {
                    insufficientEvidence(input, text, retry)
                    return@launch
                }
                preflightProblem = ChatProblem.Access
                if (request.included.isNotEmpty()) checkContext(request.included)
                currentCoroutineContext().ensureActive()
                if (ticket != submissionEpoch || !foreground) throw CancellationException()
                val turn = if (retry) state.value.turns.last().copy(answer = "", running = true, finish = null,
                    failed = false, documents = request.documents)
                    else ChatTurn(++nextTurn, input, running = true, documents = request.documents)
                accepted = turn.id
                if (!retry && state.value.draft == text) editDraft("")
                mutableState.value = state.value.copy(
                    turns = (if (retry) state.value.turns.dropLast(1) else state.value.turns) + turn,
                    phase = ChatPhase.Generating)
                var finished = false
                owner.stream(request.messages).collect { event ->
                    currentCoroutineContext().ensureActive()
                    if (ticket != submissionEpoch || !foreground) throw CancellationException()
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
                if (ticket == submissionEpoch) accepted?.let { id -> updateTurn(id) {
                    if (it.running) it.copy(running = false, finish = FinishReason.CANCELLED) else it
                } }
                throw error
            } catch (error: Exception) {
                // Provider/native exceptions can contain document URIs or input text.
                Log.w("TP1Chat", "Chat request failed in ${if (accepted == null) preflightProblem else ChatProblem.Generation}")
                if (ticket == submissionEpoch) {
                    if (accepted == null) mutableState.value = state.value.copy(problem = keepDraftError(preflightProblem),
                        preparation = if (preflightProblem == ChatProblem.Preparation) ChatPreparation.Error else state.value.preparation)
                    else updateTurn(accepted) { it.copy(running = false, failed = true, finish = FinishReason.NATIVE_FAILURE) }
                }
            } finally {
                if (ticket == submissionEpoch) mutableState.value = state.value.copy(phase = ChatPhase.Idle,
                    preparation = if (connection == null && state.value.preparation in setOf(
                        ChatPreparation.Acquiring, ChatPreparation.Loading)) ChatPreparation.Unprepared else state.value.preparation)
            }
        }
        submission!!.start()
    }

    private fun insufficientEvidence(input: String, draft: String, retry: Boolean) {
        val turn = ChatTurn(if (retry) state.value.turns.last().id else ++nextTurn, input,
            answer = getApplication<Application>().getString(R.string.chat_insufficient_evidence))
        if (!retry && state.value.draft == draft) editDraft("")
        mutableState.value = state.value.copy(turns = (if (retry) state.value.turns.dropLast(1) else state.value.turns) + turn)
    }

    fun stop() {
        if (state.value.phase !in setOf(ChatPhase.Retrieving, ChatPhase.Validating, ChatPhase.Generating)) return
        mutableState.value = state.value.copy(phase = ChatPhase.Stopping)
        connection?.cancel()
        submission?.cancel()
    }

    fun openDocument(document: DocumentReference, launch: (Intent) -> Unit) {
        if (cleared || state.value.openingDocument || state.value.turns.none { document in it.documents }) return
        mutableState.value = state.value.copy(openingDocument = true, openProblem = null)
        viewModelScope.launch {
            try {
                val intent = documentIntent(getApplication(), document)
                currentCoroutineContext().ensureActive()
                launch(intent)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                mutableState.value = state.value.copy(openProblem = when (error) {
                    is ActivityNotFoundException -> ChatOpenProblem.NoViewer
                    is SecurityException, is java.io.FileNotFoundException -> ChatOpenProblem.Unavailable
                    else -> ChatOpenProblem.Provider
                })
            } finally { mutableState.value = state.value.copy(openingDocument = false) }
        }
    }

    fun dismissOpenProblem(problem: ChatOpenProblem) {
        if (state.value.openProblem == problem) mutableState.value = state.value.copy(openProblem = null)
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
        if (connection == null && operation?.isActive != true && submission?.isActive != true) return
        val ticket = ++epoch
        ++submissionEpoch
        val previous = operation
        val pendingSubmission = submission
        retiringSubmission = pendingSubmission
        releasing = previous
        val closed = connection?.closeAsync()
        closing = closed
        connection = null
        previous?.cancel()
        pendingSubmission?.cancel()
        mutableState.value = state.value.copy(preparation = ChatPreparation.Closing,
            phase = ChatPhase.Stopping, turns = state.value.turns.map {
                if (it.running) it.copy(running = false, finish = FinishReason.CANCELLED) else it
            })
        startOperation {
            try {
                withContext(NonCancellable) {
                    previous?.join()
                    pendingSubmission?.join()
                    closed?.await()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("TP1Chat", "Chat cleanup failed", error)
            } finally {
                if (closing === closed && closed?.isCompleted == true) closing = null
                if (releasing === previous) releasing = null
                if (retiringSubmission === pendingSubmission) retiringSubmission = null
                if (ticket == epoch) {
                    mutableState.value = state.value.copy(preparation = ChatPreparation.Unprepared, phase = ChatPhase.Idle)
                    // A foreground return can arrive before native cleanup finishes.
                    if (active) prepare()
                }
            }
        }
    }

    /** The handoff blocks native preparation, not the submission that requested retrieval. */
    private suspend fun yieldForIndexing() {
        val ticket = ++epoch
        val previous = operation
        val pendingRelease = releasing
        val pendingSubmission = retiringSubmission ?: submission?.takeIf { cleared }
        val closed = connection?.closeAsync() ?: closing
        connection = null
        closing = closed
        previous?.cancel()
        mutableState.value = state.value.copy(preparation = ChatPreparation.Closing)
        try {
            withContext(NonCancellable) {
                previous?.join()
                pendingRelease?.join()
                pendingSubmission?.join()
                closed?.await()
            }
        } finally {
            if (closing === closed && closed?.isCompleted == true) closing = null
            if (ticket == epoch) {
                mutableState.value = state.value.copy(preparation = ChatPreparation.Unprepared,
                    phase = if (submission?.isCompleted == false) state.value.phase else ChatPhase.Idle)
            }
        }
    }

    override fun onCleared() {
        cleared = true
        ++epoch
        ++submissionEpoch
        unloadJob?.cancel()
        val previous = operation
        val pendingRelease = releasing
        val pendingSubmission = submission
        val oldSubmission = retiringSubmission
        closing = connection?.closeAsync() ?: closing
        val closed = closing
        connection = null
        previous?.cancel()
        pendingSubmission?.cancel()
        // Retiring owners still exclude indexing until late loads and native close have finished.
        viewModelScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            try {
                previous?.join()
                pendingRelease?.join()
                pendingSubmission?.join()
                oldSubmission?.join()
                closed?.await()
            } catch (_: Exception) {
                Log.w("TP1Chat", "Retired Chat cleanup failed")
            } finally { NativeWorkHandoff.unregister(nativeOwner) }
        }
        super.onCleared()
    }
}

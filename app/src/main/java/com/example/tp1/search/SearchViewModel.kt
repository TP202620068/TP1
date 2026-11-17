package com.example.tp1.search

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.tp1.browser.BrowserProblem
import com.example.tp1.browser.DocumentReference
import com.example.tp1.browser.documentIntent
import com.example.tp1.indexing.DocumentRetriever
import com.example.tp1.indexing.IndexingState
import com.example.tp1.indexing.RetrievalMatch
import com.example.tp1.indexing.RetrievalResult
import com.example.tp1.indexing.SEARCH_MIN_SCORE
import com.example.tp1.inference.EMBEDDING_INPUT_BYTES
import com.example.tp1.inference.embeddingInput
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SearchViewModel(
    application: Application,
    private val savedState: SavedStateHandle,
    private val retrieve: suspend (String) -> RetrievalResult,
    private val checkContext: suspend (List<RetrievalMatch>) -> Unit = { DocumentRetriever(application).checkContext(it) },
    private val intentForDocument: suspend (DocumentReference) -> Intent = { documentIntent(application, it) }
) : AndroidViewModel(application) {
    constructor(application: Application, savedState: SavedStateHandle) : this(application, savedState,
        retrieve = { DocumentRetriever(application).searchFiles(it, SEARCH_MIN_SCORE) })

    private val mutableState = MutableStateFlow(run {
        val draft = savedState.get<String>("searchDraft").orEmpty()
        if (draft.length <= EMBEDDING_INPUT_BYTES) SearchState(draft = draft)
        else {
            savedState["searchDraft"] = ""
            SearchState(inputInvalid = true)
        }
    })
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var opening: Job? = null
    private var epoch = 0L
    private var matches = emptyList<RetrievalMatch>()
    private var sources: Map<String, Pair<SourceKind, Boolean>>? = null
    private var completed: Map<String, Int>? = null
    private var foreground = false
    private var selected = false
    private var cleared = false
    private val active: Boolean get() = foreground && selected && !cleared

    fun editDraft(value: String) {
        if (value.length > EMBEDDING_INPUT_BYTES) {
            mutableState.value = state.value.copy(inputInvalid = true)
            return
        }
        if (value == state.value.draft) return
        savedState["searchDraft"] = value
        mutableState.value = state.value.copy(draft = value, inputInvalid = false)
    }

    fun submit() {
        if (!active || !state.value.canSubmit) return
        val query = state.value.draft.trim()
        try { embeddingInput(query, query = true) }
        catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(inputInvalid = true)
            return
        }
        search(query)
    }

    fun retry() {
        if (active && state.value.phase == SearchPhase.Idle) state.value.submitted?.let(::search)
    }

    private fun search(query: String) {
        cancelOperation()
        matches = emptyList()
        val ticket = epoch
        mutableState.value = state.value.copy(submitted = query, request = ticket, phase = SearchPhase.Searching,
            rows = emptyList(), problem = null, openProblem = null, opening = false)
        startOperation {
            var failure = SearchProblem.Retrieval
            try {
                val result = try { retrieve(query) }
                catch (error: IllegalArgumentException) { failure = SearchProblem.QueryLimit; throw error }
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@startOperation
                when (result) {
                    RetrievalResult.Busy -> mutableState.value = state.value.copy(phase = SearchPhase.Idle, problem = SearchProblem.Busy)
                    is RetrievalResult.Complete -> {
                        val rows = searchRows(result.matches)
                        matches = result.matches
                        mutableState.value = state.value.copy(phase = SearchPhase.Idle, rows = rows)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(ticket)) mutableState.value = state.value.copy(phase = SearchPhase.Idle, problem = when (error) {
                    is SecurityException, is FileNotFoundException -> SearchProblem.Changed
                    else -> failure
                })
            }
        }
    }

    fun setSelected(value: Boolean) {
        if (selected == value) return
        selected = value
        if (active) revalidate() else deactivate()
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (active) revalidate() else deactivate()
    }

    fun setSources(value: List<SelectedSource>, ready: Boolean) {
        val next = value.associate { it.record.uri to (it.record.kind to it.available) }
        if (state.value.showingResults && (!ready || sources != null && sources != next)) {
            invalidate(if (ready) SearchProblem.Changed else SearchProblem.Retrieval)
        }
        if (ready) sources = next
    }

    fun setIndexing(value: IndexingState) {
        val next = value.sources.mapValues { it.value.completed }
        if (completed != null && completed != next && state.value.showingResults && state.value.phase != SearchPhase.Searching) {
            invalidate(SearchProblem.Changed)
        }
        completed = next
    }

    private fun deactivate() {
        val searching = state.value.phase == SearchPhase.Searching
        cancelOperation()
        if (searching) {
            matches = emptyList()
            mutableState.value = state.value.copy(phase = SearchPhase.Idle, rows = emptyList(),
                problem = SearchProblem.Interrupted, opening = false, openProblem = null)
        } else mutableState.value = state.value.copy(
            phase = if (state.value.showingResults && state.value.problem == null) SearchPhase.Checking else SearchPhase.Idle,
            opening = false, openProblem = null)
    }

    private fun revalidate() {
        if (state.value.phase != SearchPhase.Checking) return
        val ticket = epoch
        val retained = matches
        startOperation {
            try {
                checkContext(retained)
                currentCoroutineContext().ensureActive()
                if (current(ticket)) mutableState.value = state.value.copy(phase = SearchPhase.Idle)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(ticket)) invalidate(if (error is SecurityException || error is FileNotFoundException)
                    SearchProblem.Changed else SearchProblem.Retrieval)
            }
        }
    }

    private fun invalidate(problem: SearchProblem) {
        cancelOperation()
        matches = emptyList()
        mutableState.value = state.value.copy(phase = SearchPhase.Idle, rows = emptyList(), problem = problem,
            opening = false, openProblem = null)
    }

    fun exitResults() {
        cancelOperation()
        matches = emptyList()
        mutableState.value = state.value.copy(submitted = null, rows = emptyList(), phase = SearchPhase.Idle,
            problem = null, opening = false, openProblem = null)
    }

    fun clear() {
        exitResults()
        savedState["searchDraft"] = ""
        mutableState.value = state.value.copy(draft = "", inputInvalid = false)
    }

    fun openDocument(document: DocumentReference, launch: (Intent) -> Unit) {
        if (!active || state.value.phase != SearchPhase.Idle || state.value.opening ||
            state.value.rows.none { it.document == document }) return
        val ticket = epoch
        mutableState.value = state.value.copy(opening = true, openProblem = null)
        opening = viewModelScope.launch {
            try {
                val intent = intentForDocument(document)
                currentCoroutineContext().ensureActive()
                if (current(ticket)) launch(intent)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current(ticket)) {
                    val problem = when (error) {
                        is ActivityNotFoundException -> BrowserProblem.NoViewer
                        is SecurityException -> BrowserProblem.Access
                        is FileNotFoundException -> BrowserProblem.Missing
                        else -> BrowserProblem.Provider
                    }
                    if (problem == BrowserProblem.Access || problem == BrowserProblem.Missing) invalidate(SearchProblem.Changed)
                    mutableState.value = state.value.copy(openProblem = problem)
                }
            } finally {
                if (ticket == epoch) mutableState.value = state.value.copy(opening = false)
            }
        }
    }

    fun dismissOpenProblem(problem: BrowserProblem) {
        if (state.value.openProblem == problem) mutableState.value = state.value.copy(openProblem = null)
    }

    private fun current(ticket: Long) = ticket == epoch && active

    private fun cancelOperation() {
        ++epoch
        operation?.cancel()
        opening?.cancel()
    }

    private fun startOperation(block: suspend () -> Unit) {
        val previous = operation
        operation = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // A cancelled replacement must still carry the previous native-cleanup barrier forward.
            withContext(NonCancellable) { previous?.join() }
            currentCoroutineContext().ensureActive()
            block()
        }
        operation!!.start()
    }

    override fun onCleared() {
        cleared = true
        cancelOperation()
        super.onCleared()
    }
}

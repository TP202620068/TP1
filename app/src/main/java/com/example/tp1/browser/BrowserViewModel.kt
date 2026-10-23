package com.example.tp1.browser

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class BrowserViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {
    private val provider = DocumentBrowser(application.contentResolver)
    private var sources = emptyList<SelectedSource>()
    private var load: Job? = null
    private var opening: Job? = null
    private val restoredPath = (savedState.get<ArrayList<String>>("browserUris") ?: arrayListOf())
        .zip(savedState.get<ArrayList<String>>("browserNames") ?: arrayListOf()) { uri, name -> BrowserFolder(uri, name) }
    private val mutableState = MutableStateFlow(BrowserState(
        sourceUri = savedState.get<String>("browserSource").takeIf { restoredPath.isNotEmpty() }, path = restoredPath
    ))
    val state = mutableState.asStateFlow()

    fun setSources(value: List<SelectedSource>, refresh: Boolean = false) {
        sources = value.filter { it.available }
        val next = state.value.reconcile(sources.filter { it.record.kind == SourceKind.Tree }.map { it.record.uri }.toSet())
        if (next != state.value) {
            load?.cancel()
            opening?.cancel()
            publish(next.copy(opening = false))
        }
        if (state.value.atRoots) showRoots()
        else if (refresh || (!state.value.loading && state.value.entries.isEmpty() && state.value.problem == null)) reload()
    }

    fun enter(entry: BrowserEntry) {
        if (sources.none { it.record.uri == entry.sourceUri && it.record.kind == SourceKind.Tree }) return
        val next = state.value.enter(entry)
        if (next == state.value) return
        publish(next)
        reload()
    }

    fun ancestor(index: Int) {
        val next = state.value.ancestor(index)
        if (next == state.value) return
        load?.cancel()
        opening?.cancel()
        publish(next.copy(opening = false))
        if (next.atRoots) showRoots() else reload()
    }

    fun back() = ancestor(state.value.path.lastIndex - 1)

    fun dismissMessage(message: BrowserProblem) {
        if (state.value.message == message) publish(state.value.copy(message = null))
    }

    fun reload() {
        val source = sources.firstOrNull { it.record.uri == state.value.sourceUri } ?: return
        val folder = state.value.path.lastOrNull() ?: return
        load?.cancel()
        opening?.cancel()
        publish(state.value.beginLoad().copy(opening = false))
        val token = state.value.request
        load = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { provider.children(source, folder) }
                publish(state.value.complete(token, result.entries, pending = result.pending))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publish(state.value.complete(token, emptyList(), problem(error)))
            }
        }
    }

    fun open(entry: BrowserEntry, launch: (Intent) -> Unit) {
        if (entry.directory || entry !in state.value.entries || state.value.opening) return
        val source = sources.firstOrNull { it.record.uri == entry.sourceUri } ?: return
        val token = state.value.request
        publish(state.value.copy(opening = true, message = null))
        opening = viewModelScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { provider.viewIntent(source, entry) }
                if (state.value.request == token && entry in state.value.entries) launch(intent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (state.value.request == token) publish(state.value.copy(message = problem(error)))
            } finally {
                if (state.value.request == token) publish(state.value.copy(opening = false))
            }
        }
    }

    private fun showRoots() = publish(state.value.copy(entries = sources.map(provider::root), loading = false, pending = false))

    private fun publish(value: BrowserState) {
        savedState["browserSource"] = value.sourceUri
        savedState["browserUris"] = ArrayList(value.path.map { it.uri })
        savedState["browserNames"] = ArrayList(value.path.map { it.name })
        mutableState.value = value
    }

    private fun problem(error: Exception) = when (error) {
        is ActivityNotFoundException -> BrowserProblem.NoViewer
        // DocumentsProvider can flatten a missing descendant into IllegalArgumentException over Binder.
        is FileNotFoundException, is IllegalArgumentException -> BrowserProblem.Missing
        is SecurityException -> BrowserProblem.Access
        else -> BrowserProblem.Provider
    }
}

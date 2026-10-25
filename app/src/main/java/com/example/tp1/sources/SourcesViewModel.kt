package com.example.tp1.sources

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.example.tp1.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class SourcesViewModel(application: Application, private val savedState: SavedStateHandle) :
    AndroidViewModel(application) {
    private val store = SourceStore(application)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(SourceState(
        selecting = savedState["sourceSelectionOpen"] ?: false
    ))
    val state = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() = operation(refresh = true) { restoreSources() }

    private suspend fun restoreSources() {
        val restored = withContext(Dispatchers.IO) { store.restore() }
        publish(state.value.copy(
            restoring = false,
            storageReady = true,
            sources = restored.sources,
            message = state.value.message?.takeUnless {
                it == R.string.source_records_damaged || it == R.string.source_restore_error
            } ?: R.string.source_records_damaged.takeIf { restored.damagedRecords }
        ))
    }

    fun add(uris: List<String>, kind: SourceKind, flags: Int) {
        if (uris.isEmpty()) return
        operation {
            check(state.value.storageReady)
            val update = withContext(Dispatchers.IO) { store.add(state.value.sources, uris, kind, flags) }
            publish(state.value.copy(
                sources = update.sources,
                message = if (update.rejected) R.string.source_could_not_save else null
            ))
        }
    }

    fun remove(uri: String) = operation {
        val next = withContext(Dispatchers.IO) { store.remove(state.value.sources, uri) }
        publish(state.value.copy(sources = next, message = null))
    }

    fun selectSources() = publish(state.value.copy(selecting = true))

    fun closeSelection() {
        publish(state.value.closeSelection())
    }

    fun pickerUnavailable() = publish(state.value.copy(message = R.string.source_picker_unavailable))

    private fun publish(value: SourceState) {
        savedState["sourceSelectionOpen"] = value.selecting
        mutableState.value = value
    }

    private fun operation(refresh: Boolean = false, block: suspend () -> Unit) = viewModelScope.launch {
        mutex.withLock {
            mutableState.value = state.value.copy(busy = true)
            try {
                if (!refresh && !state.value.storageReady) restoreSources()
                block()
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                publish(state.value.copy(
                    restoring = false,
                    storageReady = false,
                    message = if (refresh) R.string.source_restore_error else R.string.source_storage_error
                ))
            } finally {
                mutableState.value = state.value.copy(busy = false)
            }
        }
    }
}

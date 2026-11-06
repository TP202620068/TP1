package com.example.tp1.indexing

import android.content.Context
import android.database.sqlite.SQLiteException
import com.example.tp1.browser.BrowserEntry
import com.example.tp1.browser.BrowserFolder
import com.example.tp1.browser.DirectoryListing
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.extraction.ExtractionFailure
import com.example.tp1.extraction.ExtractionWork
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal enum class IndexingProblem { Document, Unsupported, NeedsOcr, Discovery, Model, Storage, Access }
internal data class IndexingFailure(val name: String, val problem: IndexingProblem)
internal data class SourceIndexingState(
    val discovering: Boolean = true,
    val pending: Int = 0,
    val running: Boolean = false,
    val waitingForChat: Boolean = false,
    val completed: Int = 0,
    val failures: List<IndexingFailure> = emptyList(),
    val incomplete: Boolean = false
)
internal data class IndexingState(
    val sources: Map<String, SourceIndexingState> = emptyMap(),
    val blocked: IndexingProblem? = null
)

internal class IndexModelFailure(cause: Exception) : IOException("Cannot open indexing model", cause)
internal class IndexStorageFailure(cause: IOException) : IOException("Cannot open index storage", cause)

/** Process-owned work. Disk membership and completed snapshots are the restart checkpoint. */
internal class IndexingCoordinator(
    private val scope: CoroutineScope,
    private val root: (SelectedSource) -> BrowserEntry,
    private val children: suspend (SelectedSource, BrowserFolder) -> DirectoryListing,
    private val index: suspend (SelectedSource, BrowserEntry, () -> Unit) -> IndexResult,
    private val checkSource: (SelectedSource) -> Unit = {}
) {
    private class Admission(val source: SelectedSource, parent: Job?) {
        val job = SupervisorJob(parent)
        val seen = mutableSetOf<String>()
        var status = SourceIndexingState()
    }
    private data class Pending(val admission: Admission, val entry: BrowserEntry)

    private val admissions = linkedMapOf<String, Admission>()
    private var known = emptyMap<String, Boolean>()
    private var restored = false
    private var restoreFailed = false
    private var blocked: IndexingProblem? = null
    private val listings = Semaphore(2)
    private val queue = Channel<Pending>(32)
    private val mutableState = MutableStateFlow(IndexingState())
    val state = mutableState.asStateFlow()

    init {
        scope.launch {
            for (pending in queue) {
                val admission = pending.admission
                val task = synchronized(ExtractionWork.sourceLock) {
                    if (!current(admission)) null
                    else scope.async(admission.job) { execute(pending) }
                } ?: continue
                try { task.await() }
                catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    update(admission) { copy(pending = (this.pending - 1).coerceAtLeast(0), running = false,
                        incomplete = true) }
                }
            }
        }
    }

    fun restore(sources: List<SelectedSource>) = synchronized(ExtractionWork.sourceLock) {
        restoreFailed = false
        val next = sources.associate { it.record.uri to it.available }
        admissions.keys.toList().filter { next[it] != true }.forEach(::cancelSource)
        sources.filter { it.available && (!restored || known[it.record.uri] != true) }.forEach(::admitSource)
        known = next
        restored = true
        publish()
    }

    fun restoreError() = synchronized(ExtractionWork.sourceLock) {
        restoreFailed = true
        publish()
    }

    fun admit(sources: List<SelectedSource>) = synchronized(ExtractionWork.sourceLock) {
        sources.filter { it.available }.forEach { source ->
            known = known + (source.record.uri to true)
            admitSource(source)
        }
        publish()
    }

    fun retry(uri: String) = synchronized(ExtractionWork.sourceLock) {
        val source = admissions[uri]?.source ?: return@synchronized
        cancelSource(uri)
        blocked = null
        admitSource(source)
        publish()
    }

    fun cancelSource(uri: String) = synchronized(ExtractionWork.sourceLock) {
        admissions.remove(uri)?.job?.cancel()
        known = known + (uri to false)
        publish()
    }

    private fun admitSource(source: SelectedSource) {
        if (admissions[source.record.uri]?.job?.isActive == true) return
        val admission = Admission(source, scope.coroutineContext[Job])
        admissions[source.record.uri] = admission
        scope.launch(admission.job) { discover(admission) }
    }

    private suspend fun discover(admission: Admission) {
        try {
            checkAdmission(admission)
            val entry = root(admission.source)
            if (admission.source.record.kind == SourceKind.Document) enqueue(admission, entry)
            else {
                val folders = ArrayDeque<BrowserFolder>()
                val visited = mutableSetOf<String>()
                folders.add(BrowserFolder(entry.uri, entry.metadata.name))
                while (folders.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val folder = folders.removeFirst()
                    if (!visited.add(folder.uri)) continue
                    try {
                        val listing = listings.withPermit { children(admission.source, folder) }
                        checkAdmission(admission)
                        if (listing.pending) update(admission) { copy(incomplete = true) }
                        for (child in listing.entries) {
                            currentCoroutineContext().ensureActive()
                            if (child.directory) folders.add(BrowserFolder(child.uri, child.metadata.name))
                            else enqueue(admission, child)
                        }
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) {
                        recheckFailure(admission, error)
                        update(admission) { copy(incomplete = true,
                            failures = failures + IndexingFailure(folder.name, IndexingProblem.Discovery)) }
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            recheckFailure(admission, error)
            update(admission) { copy(incomplete = true,
                failures = failures + IndexingFailure(admission.source.record.name.orEmpty(), IndexingProblem.Discovery)) }
        } finally { update(admission) { copy(discovering = false) } }
    }

    private suspend fun enqueue(admission: Admission, entry: BrowserEntry) {
        synchronized(ExtractionWork.sourceLock) {
            checkAdmission(admission)
            if (!admission.seen.add(entry.uri)) return
            admission.status = admission.status.copy(pending = admission.status.pending + 1)
            publish()
        }
        queue.send(Pending(admission, entry))
    }

    private suspend fun execute(pending: Pending) {
        val (admission, entry) = pending
        var wait = 250L
        while (true) {
            state.first { it.blocked == null }
            currentCoroutineContext().ensureActive()
            try {
                checkAdmission(admission)
                update(admission) { copy(running = true, waitingForChat = false) }
                when (index(admission.source, entry) { checkAdmission(admission) }) {
                    is IndexResult.Complete -> {
                        update(admission) { copy(pending = this.pending - 1, running = false, completed = completed + 1) }
                        return
                    }
                    IndexResult.Busy -> {
                        update(admission) { copy(running = false, waitingForChat = true) }
                        delay(wait)
                        wait = (wait * 2).coerceAtMost(4_000L)
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                val problem = when (val failure = recheckFailure(admission, error)) {
                    is IndexModelFailure -> IndexingProblem.Model
                    is IndexStorageFailure, is SQLiteException, is org.json.JSONException -> IndexingProblem.Storage
                    is ExtractionFailure -> when (failure.code) {
                        "storage" -> IndexingProblem.Storage
                        "needsOcr" -> IndexingProblem.NeedsOcr
                        "unsupported" -> IndexingProblem.Unsupported
                        else -> IndexingProblem.Document
                    }
                    else -> IndexingProblem.Document
                }
                if (problem == IndexingProblem.Model || problem == IndexingProblem.Storage) {
                    synchronized(ExtractionWork.sourceLock) {
                        if (current(admission)) {
                            blocked = problem
                            admission.status = admission.status.copy(running = false, waitingForChat = false)
                            publish()
                        }
                    }
                } else {
                    update(admission) { copy(pending = this.pending - 1, running = false,
                        failures = failures + IndexingFailure(entry.metadata.name, problem)) }
                    return
                }
            }
        }
    }

    private fun current(admission: Admission) = admissions[admission.source.record.uri] === admission && admission.job.isActive

    private fun recheckFailure(admission: Admission, error: Exception): Exception = try {
        checkAdmission(admission)
        error
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (storage: IndexStorageFailure) { storage }

    private fun checkAdmission(admission: Admission) = synchronized(ExtractionWork.sourceLock) {
        if (!current(admission)) throw CancellationException("Source admission ended")
        try { checkSource(admission.source) }
        catch (error: CancellationException) { throw error }
        catch (_: SecurityException) {
            admission.status = admission.status.copy(discovering = false, pending = 0, running = false,
                waitingForChat = false, incomplete = true,
                failures = admission.status.failures + IndexingFailure(admission.source.record.name.orEmpty(), IndexingProblem.Access))
            known = known + (admission.source.record.uri to false)
            admission.job.cancel()
            publish()
            throw CancellationException("Source access ended")
        } catch (error: Exception) {
            restoreFailed = true
            publish()
            throw IndexStorageFailure(IOException("Cannot verify saved source", error))
        }
    }

    private fun update(admission: Admission, change: SourceIndexingState.() -> SourceIndexingState) =
        synchronized(ExtractionWork.sourceLock) {
            if (current(admission)) { admission.status = admission.status.change(); publish() }
        }

    private fun publish() {
        mutableState.value = IndexingState(admissions.mapValues { it.value.status },
            if (restoreFailed) IndexingProblem.Storage else blocked)
    }

    suspend fun close() {
        scope.coroutineContext[Job]?.cancel()
        scope.coroutineContext[Job]?.join()
    }

    companion object {
        private var process: IndexingCoordinator? = null

        fun get(context: Context): IndexingCoordinator = synchronized(ExtractionWork.sourceLock) {
            process ?: run {
                val application = context.applicationContext
                val browser = DocumentBrowser(application.contentResolver)
                IndexingCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.IO), browser::root,
                    { source, folder ->
                        val coroutine = currentCoroutineContext()
                        browser.children(source, folder) { coroutine.ensureActive() }
                    },
                    { source, entry, check -> DocumentIndexer(application, checkAdmission = check).index(source, entry, refresh = false) },
                    { source -> ExtractionWork.requireSavedRead(application, source.record) }
                ).also { process = it }
            }
        }

        fun invalidate(uri: String) = synchronized(ExtractionWork.sourceLock) { process?.cancelSource(uri); Unit }

        /** Install before Activity startup; callers await close before replacing an existing owner. */
        fun replaceForTests(value: IndexingCoordinator?): IndexingCoordinator? = synchronized(ExtractionWork.sourceLock) {
            process.also { process = value }
        }
    }
}

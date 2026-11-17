package com.example.tp1

import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.browser.DirectoryListing
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.indexing.IndexResult
import com.example.tp1.indexing.IndexingCoordinator
import com.example.tp1.inference.Model
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Install before Activity startup, including its initial saved-source recovery. */
class OfflineIndexingRule internal constructor(download: suspend (Model) -> Unit = {}) : ExternalResource() {
    private val acquisition = OfflineModelAcquisitionRule(download)

    internal fun install() = runBlocking {
        acquisition.install()
        IndexingCoordinator.replaceForTests(null)?.close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val browser = DocumentBrowser(context.contentResolver)
        IndexingCoordinator.replaceForTests(IndexingCoordinator(
            CoroutineScope(SupervisorJob() + Dispatchers.IO), browser::root,
            { _, _ -> DirectoryListing(emptyList(), false) },
            { _, _, check -> check(); IndexResult.Complete(0, "offline-ui", 0, true) }
        ))
        Unit
    }

    internal fun uninstall() = runBlocking {
        try { IndexingCoordinator.replaceForTests(null)?.close() }
        finally { acquisition.uninstall() }
        Unit
    }
    override fun before() = install()
    override fun after() = uninstall()
}

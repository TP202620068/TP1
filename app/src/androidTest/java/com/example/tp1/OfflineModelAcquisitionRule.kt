package com.example.tp1

import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelAcquisition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.rules.ExternalResource

/** Replace acquisition before onCreate, not at the later CREATED lifecycle callback. */
class OfflineModelAcquisitionRule internal constructor(
    private val download: suspend (Model) -> Unit = {}
) : ExternalResource() {
    internal fun install() = runBlocking {
        ModelAcquisition.replaceForTests(null)?.close()
        ModelAcquisition.replaceForTests(ModelAcquisition(CoroutineScope(SupervisorJob() + Dispatchers.IO), download))
        Unit
    }

    internal fun uninstall() = runBlocking { ModelAcquisition.replaceForTests(null)?.close(); Unit }
    override fun before() = install()
    override fun after() = uninstall()
}

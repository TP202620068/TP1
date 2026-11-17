package com.example.tp1.inference

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Process-owned disk acquisition. Waiting consumers never own or cancel the shared download. */
internal class ModelAcquisition(
    private val scope: CoroutineScope,
    private val download: suspend (Model) -> Unit,
    private val reportFailure: (Model, Exception) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    private val attempts = mutableMapOf<Model, Deferred<Unit>>()
    private var started = false

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            request(Model.JinaV5NanoRetrieval)
            request(Model.Test)
        }
    }

    suspend fun acquire(model: Model) {
        currentCoroutineContext().ensureActive()
        request(model).await()
    }

    private fun request(model: Model): Deferred<Unit> = synchronized(lock) {
        attempts[model]?.takeUnless { it.isCompleted } ?: scope.async {
            try { download(model) }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                reportFailure(model, error)
                throw error
            }
        }.also { attempts[model] = it }
    }

    suspend fun close() { scope.coroutineContext[Job]?.cancelAndJoin() }

    companion object {
        private var process: ModelAcquisition? = null

        fun get(context: Context): ModelAcquisition = synchronized(this) {
            process ?: ModelAcquisition(
                CoroutineScope(SupervisorJob() + Dispatchers.IO),
                ModelManager(context.applicationContext)::download,
                { model, error -> Log.w("TP1Models", "Acquisition failed for $model", error) }
            ).also { process = it }
        }

        /** Install before Activity startup; close the previous owner before replacing it. */
        fun replaceForTests(value: ModelAcquisition?): ModelAcquisition? = synchronized(this) {
            process.also { process = value }
        }
    }
}

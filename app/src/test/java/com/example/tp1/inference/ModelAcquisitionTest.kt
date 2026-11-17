package com.example.tp1.inference

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ModelAcquisitionTest {
    @Test fun startupAndConcurrentConsumersShareBothAcquisitions() = runBlocking<Unit> {
        val entered = Model.entries.associateWith { CompletableDeferred<Unit>() }
        val releases = Model.entries.associateWith { CompletableDeferred<Unit>() }
        val calls = ConcurrentHashMap<Model, AtomicInteger>()
        val owner = ModelAcquisition(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { model ->
            calls.computeIfAbsent(model) { AtomicInteger() }.incrementAndGet()
            entered.getValue(model).complete(Unit)
            releases.getValue(model).await()
        })
        try {
            owner.start()
            withTimeout(5_000) { entered.values.forEach { it.await() } }
            repeat(5) { owner.start() }
            val waiting = Model.entries.flatMap { model -> List(4) { async { owner.acquire(model) } } }
            // Let every consumer enter acquire before completing the shared work.
            kotlinx.coroutines.yield()
            assertEquals(Model.entries.toSet(), calls.keys)
            assertTrue(calls.values.all { it.get() == 1 })
            releases.values.forEach { it.complete(Unit) }
            withTimeout(5_000) { waiting.forEach { it.await() } }
            assertTrue(calls.values.all { it.get() == 1 })
        } finally { owner.close() }
    }

    @Test fun oneModelFailureDoesNotCancelTheOtherAndLaterRequestRetries() = runBlocking<Unit> {
        val failed = CompletableDeferred<Unit>()
        val healthy = CompletableDeferred<Unit>()
        val calls = ConcurrentHashMap<Model, AtomicInteger>()
        val owner = ModelAcquisition(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), { model ->
            val attempt = calls.computeIfAbsent(model) { AtomicInteger() }.incrementAndGet()
            if (model == Model.JinaV5NanoRetrieval && attempt == 1) throw IOException("Offline fixture")
            if (model == Model.Test) healthy.complete(Unit)
        }, { model, _ -> if (model == Model.JinaV5NanoRetrieval) failed.complete(Unit) })
        try {
            owner.start()
            withTimeout(5_000) { failed.await(); healthy.await() }
            owner.acquire(Model.JinaV5NanoRetrieval)
            assertEquals(2, calls.getValue(Model.JinaV5NanoRetrieval).get())
            assertEquals(1, calls.getValue(Model.Test).get())
        } finally { owner.close() }
    }

    @Test fun cancellingWaiterDoesNotCancelDownloadAndLaterAcquisitionRechecks() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val owner = ModelAcquisition(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
        })
        try {
            val cancelled = async { owner.acquire(Model.Test) }
            withTimeout(5_000) { entered.await() }
            cancelled.cancelAndJoin()
            val waiting = async { owner.acquire(Model.Test) }
            kotlinx.coroutines.yield()
            assertEquals(1, calls.get())
            release.complete(Unit)
            withTimeout(5_000) { waiting.await() }
            owner.acquire(Model.Test)
            assertEquals(2, calls.get())
        } finally { owner.close() }
    }
}

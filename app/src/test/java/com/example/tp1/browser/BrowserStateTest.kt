package com.example.tp1.browser

import com.example.tp1.sources.SourceMetadata
import org.junit.Assert.*
import org.junit.Test

class BrowserStateTest {
    private fun folder(uri: String, source: String = "tree") = BrowserEntry(
        source, uri, SourceMetadata("mismo nombre", "vnd.android.document/directory", null, null), true
    )

    @Test fun nestedNavigationUsesIdentityAndCannotEscapeOrAcceptUnlistedEntries() {
        val root = folder("root")
        val child = folder("opaque-child")
        val grandchild = folder("unrelated-looking-opaque-id")
        val roots = BrowserState(entries = listOf(root))
        assertEquals(roots, roots.enter(child))
        val first = roots.enter(root).copy(entries = listOf(child))
        val second = first.enter(child).copy(entries = listOf(grandchild))
        val third = second.enter(grandchild)
        assertEquals(listOf("root", "opaque-child", "unrelated-looking-opaque-id"), third.path.map { it.uri })
        assertEquals(listOf("root", "opaque-child"), third.ancestor(1).path.map { it.uri })
        assertEquals(listOf("root"), third.ancestor(0).path.map { it.uri })
        assertTrue(third.ancestor(-1).atRoots)
        assertTrue(third.ancestor(-1).path.isEmpty())
        assertEquals(roots, roots.ancestor(-1))
        val foreign = folder("foreign", "another-tree")
        assertEquals(first.copy(entries = listOf(foreign)), first.copy(entries = listOf(foreign)).enter(foreign))
    }

    @Test fun healthyRefreshPreservesPathAndScopeLossInvalidatesPendingResults() {
        val root = folder("root")
        val state = BrowserState(entries = listOf(root)).enter(root).beginLoad()
        assertEquals(state, state.reconcile(setOf("tree", "other")))
        val lost = state.reconcile(setOf("other"))
        assertTrue(lost.atRoots)
        assertFalse(lost.loading)
        assertEquals(BrowserProblem.Access, lost.message)
        assertEquals(lost, lost.complete(state.request, listOf(folder("late"))))
    }

    @Test fun supersededLoadsIncludingReturnToSameFolderCannotReplaceNewerResults() {
        val root = folder("root")
        val old = BrowserState(entries = listOf(root)).enter(root).beginLoad()
        val newer = old.beginLoad()
        assertEquals(newer, newer.complete(old.request, listOf(folder("stale"))))
        val completed = newer.complete(newer.request, emptyList())
        assertFalse(completed.loading)
        assertNull(completed.problem)
        val failed = newer.complete(newer.request, emptyList(), BrowserProblem.Provider)
        assertEquals(BrowserProblem.Provider, failed.problem)
        assertFalse(failed.loading)
        val back = old.ancestor(-1).copy(entries = listOf(root)).enter(root).beginLoad()
        assertEquals(back, back.complete(old.request, listOf(folder("stale"))))
    }
}

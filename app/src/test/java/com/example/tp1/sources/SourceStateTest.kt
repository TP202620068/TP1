package com.example.tp1.sources

import org.junit.Assert.*
import org.junit.Test

class SourceStateTest {
    private val document = SourceRecord("content://provider/document/One%2Ftwo", SourceKind.Document, "igual.txt")
    private val selected = SelectedSource(document, SourceMetadata("igual.txt", "text/plain", null, null))

    @Test
    fun identityKeepsDifferentUrisEvenWhenNamesMatch() {
        val other = document.copy(uri = "content://provider/document/one%2Ftwo")
        assertEquals(listOf(document, other), listOf(document, document.copy(name = "otro"), other).deduplicated())
    }

    @Test
    fun selectionIsOptionalAndCanCloseWithoutUsableSources() {
        val fresh = SourceState(restoring = false)
        assertFalse(fresh.selecting)
        val emptyEditor = fresh.copy(selecting = true)
        assertTrue(emptyEditor.canCloseSelection)
        assertFalse(emptyEditor.closeSelection().selecting)
        val unavailable = emptyEditor.copy(sources = listOf(selected.copy(metadata = null)))
        assertFalse(unavailable.closeSelection().selecting)
        assertEquals(unavailable.sources, unavailable.closeSelection().sources)
        val busy = emptyEditor.copy(busy = true)
        assertEquals(busy, busy.closeSelection())
        val restoring = emptyEditor.copy(restoring = true)
        assertEquals(restoring, restoring.closeSelection())
        assertFalse(fresh.copy(sources = listOf(selected)).selecting)
    }
}

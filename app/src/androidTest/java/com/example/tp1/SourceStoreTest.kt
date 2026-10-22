package com.example.tp1

import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceRecord
import com.example.tp1.sources.SourceStore
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import com.example.tp1.sources.SelectedSource

class SourceStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = SourceRecord(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTP1-m2",
        SourceKind.Tree,
        "TP1-m2"
    )

    @Test
    fun recordsRoundTripWithoutPretendingUriTextIsAuthorization() {
        val file = File(context.cacheDir, "source-roundtrip.json").apply { delete() }
        try {
            val store = SourceStore(context, file)
            assertTrue(store.restore().sources.isEmpty())
            store.writeRecords(listOf(tree, tree.copy(name = "duplicada")))
            val restored = SourceStore(context, file).restore().sources
            assertEquals(listOf(tree), restored.map { it.record })
            assertFalse(restored.single().available)
            assertFalse(restored.single().record.uri.isEmpty())
        } finally { file.delete() }
    }

    @Test
    fun malformedEntriesArePreservedAndWholeFileCorruptionIsNotOverwritten() {
        val file = File(context.cacheDir, "source-corrupt.json")
        try {
            file.writeText("""[{"future":"keep"}]""")
            val store = SourceStore(context, file)
            assertTrue(store.restore().damagedRecords)
            store.writeRecords(listOf(tree))
            assertEquals("keep", JSONArray(file.readText()).getJSONObject(1).getString("future"))
            file.writeText("broken JSON")
            val broken = SourceStore(context, file)
            assertThrows(Exception::class.java) { broken.restore() }
            assertThrows(IllegalStateException::class.java) { broken.writeRecords(emptyList()) }
            assertEquals("broken JSON", file.readText())
        } finally { file.delete() }
    }

    @Test
    fun failedWritesAreReported() {
        val blocker = File(context.cacheDir, "source-parent-file").apply { writeText("not a directory") }
        try {
            val store = SourceStore(context, File(blocker, "sources.json"))
            store.restore()
            assertThrows(Exception::class.java) { store.writeRecords(listOf(tree)) }
            assertEquals("not a directory", blocker.readText())
        } finally { blocker.delete() }
    }

    @Test
    fun temporaryAndInvalidResultsPreserveExistingSelections() {
        val file = File(context.cacheDir, "source-rejected.json").apply { delete() }
        try {
            val store = SourceStore(context, file)
            store.restore()
            val existing = listOf(SelectedSource(tree.copy(uri = tree.uri + "-other"), null))
            store.writeRecords(existing.map { it.record })
            val result = store.add(existing, listOf(tree.uri, "file:///storage/not-authorized"), SourceKind.Tree, 0)
            assertTrue(result.rejected)
            assertEquals(existing, result.sources)
            assertEquals(existing.map { it.record }, SourceStore(context, file).restore().sources.map { it.record })
        } finally { file.delete() }
    }

    @Test
    fun missingProviderKeepsItsRecordAndCanBeRemoved() {
        val file = File(context.cacheDir, "source-provider-missing.json").apply { delete() }
        try {
            val record = SourceRecord("content://com.example.missing/document/opaque-id", SourceKind.Document, "Ausente.txt")
            val store = SourceStore(context, file)
            store.restore()
            store.writeRecords(listOf(record))
            val restored = store.restore()
            assertFalse(restored.damagedRecords)
            assertEquals(record, restored.sources.single().record)
            assertFalse(restored.sources.single().available)
            assertTrue(store.remove(restored.sources, record.uri).isEmpty())
            assertTrue(store.restore().sources.isEmpty())
        } finally { file.delete() }
    }
}

package com.example.tp1

import android.content.Intent
import androidx.core.net.toUri
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceRecord
import com.example.tp1.sources.SourceStore
import com.example.tp1.sources.sourcePickerIntent
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import android.util.AtomicFile
import com.example.tp1.sources.SelectedSource

class SourceStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val tree = SourceRecord(
        "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTP1-m2",
        SourceKind.Tree,
        "TP1-m2"
    )

    @Test
    fun pickerIntentsUseDurableLocalReadOnlyDocumentActions() {
        val files = sourcePickerIntent(SourceKind.Document)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, files.action)
        assertTrue(files.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertTrue(files.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertTrue(files.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", files.type)
        val tree = sourcePickerIntent(SourceKind.Tree)
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, tree.action)
        assertEquals(0, (files.flags or tree.flags) and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test
    fun failedRemovalAndUnknownRecordsKeepRetainedReadGrant() {
        val robot = PickerRobot()
        robot.resetSources()
        val file = File(context.cacheDir, "removal-durability.json").apply { delete() }
        val pending = File(file.path + ".new")
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            val uri = try {
                context.contentResolver.call("content://com.example.tp1.test.documents".toUri(),
                    "fixture-authorize", "file", null)!!.getString("uri")!!
            } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
            val store = SourceStore(context, file)
            store.restore()
            val selected = store.add(emptyList(), listOf(uri), SourceKind.Document,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).sources
            val saved = file.readText()
            // A nonempty directory blocks AtomicFile's next write without damaging the saved file.
            check(pending.mkdir())
            File(pending, "blocker").writeText("keep")
            assertThrows(Exception::class.java) { store.remove(selected, uri) }
            assertEquals(saved, file.readText())
            robot.assertReadOnlyGrants(1)
            assertTrue(SourceStore(context, file).restore().sources.single().available)
            pending.deleteRecursively()

            val records = JSONArray(saved).put(org.json.JSONObject().put("uri", uri).put("kind", "Future"))
            file.writeText(records.toString())
            val restored = store.restore()
            assertTrue(restored.damagedRecords)
            assertTrue(store.remove(restored.sources, uri).isEmpty())
            robot.assertReadOnlyGrants(1)
            assertEquals("Future", JSONArray(file.readText()).getJSONObject(0).getString("kind"))
            context.contentResolver.openInputStream(uri.toUri())!!.use { assertEquals('T'.code, it.read()) }

            file.writeText(saved)
            assertTrue(store.remove(store.restore().sources, uri).isEmpty())
            robot.assertReadOnlyGrants(0)
            assertTrue(SourceStore(context, file).restore().sources.isEmpty())
        } finally {
            try {
                pending.deleteRecursively()
                file.delete()
            } finally { robot.cleanUp() }
        }
    }

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

    @Test
    fun committedRemovalWithFailedVerificationCannotResaveStaleSources() {
        val file = File(context.cacheDir, "source-uncertain-write.json")
        var failAfterCommit = false
        var failRead = false
        val storage = object : AtomicFile(file) {
            override fun finishWrite(stream: FileOutputStream?) {
                super.finishWrite(stream)
                if (failAfterCommit) { failAfterCommit = false; failRead = true }
            }

            override fun openRead(): FileInputStream {
                if (failRead) { failRead = false; throw IOException("Simulated post-commit verification failure") }
                return super.openRead()
            }
        }
        try {
            val store = SourceStore(context, file, storage)
            store.restore()
            store.writeRecords(listOf(tree))
            val stale = store.restore().sources
            failAfterCommit = true
            assertThrows(IOException::class.java) { store.remove(stale, tree.uri) }
            assertEquals(0, JSONArray(file.readText()).length())
            assertThrows(IllegalStateException::class.java) { store.writeRecords(stale.map { it.record }) }
            assertThrows(IllegalStateException::class.java) { store.add(stale, listOf(tree.uri), SourceKind.Tree, 0) }
            assertThrows(IllegalStateException::class.java) { store.remove(stale, tree.uri) }
            assertTrue(store.restore().sources.isEmpty())
            val next = tree.copy(uri = tree.uri + "-new")
            store.writeRecords(listOf(next))
            assertEquals(listOf(next), store.restore().sources.map { it.record })
        } finally { storage.delete() }
    }
}

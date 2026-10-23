package com.example.tp1

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.net.toUri
import androidx.compose.ui.graphics.asAndroidBitmap
import com.example.tp1.browser.BrowserViewModel
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceStore
import com.example.tp1.sources.sourcePickerIntent
import com.example.tp1.sources.SourcesViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class SourceSelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val robot = PickerRobot()
    private val first = "TP1-contrato.txt"
    private val second = "TP1-Contrato_de_alquiler_firmado_y_revisado_octubre_2026.txt"

    @Before
    fun prepare() {
        robot.resetSources()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNode(hasText("Explorador") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
    }

    @After fun cleanUp() {
        robot.cleanUp()
        shell("rm -rf /sdcard/Download/${robot.folderName}")
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText() }

    private fun fixtureCall(method: String, arg: String? = null): Bundle {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        return try {
            checkNotNull(instrumentation.targetContext.contentResolver.call(
                "content://com.example.tp1.test.documents".toUri(), method, arg, null
            ))
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        java.io.File(context.getExternalFilesDir(null), "source-status-$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private fun openSelection() {
        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithText("Añadir archivos").assertIsDisplayed()
    }

    @Test
    fun freshLaunchStaysInExplorerAndEmptySelectionCanBeDismissed() {
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        compose.onNodeWithText("Pulsa Añadir para elegir archivos o carpetas.").assertIsDisplayed()
        compose.onNodeWithText("Fuentes").assertDoesNotExist()
        compose.onNode(hasText("Chat") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithText("Vista previa").assertIsDisplayed()
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        openSelection()
        compose.onNodeWithText("Listo").assertIsEnabled()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText("Añadir archivos").assertIsDisplayed()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        openSelection()
        compose.onNodeWithText("Listo").performClick()
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
    }

    @Test
    fun nativePickersPersistDeduplicateCancelAndRemoveWithoutDeletingOriginals() {
        robot.createFile(first)
        robot.createFile(second)
        openSelection()
        compose.onNodeWithText("Listo").assertIsEnabled()
        compose.onNodeWithText("Fuentes").assertDoesNotExist()
        compose.onNodeWithText("Elegir archivos").performClick()
        robot.back()
        compose.awaitSources()
        compose.onNodeWithText("Aún no has añadido archivos ni carpetas.").assertIsDisplayed()
        compose.onNodeWithText("Listo").assertIsEnabled()

        compose.onNodeWithText("Elegir archivos").performClick()
        robot.pickDocument(first)
        compose.awaitSources()
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.onNodeWithText("Añadir archivos").assertIsDisplayed()
        compose.onNodeWithText("Listo").assertIsEnabled()
        robot.assertReadOnlyGrants(1)

        compose.onNodeWithText("Elegir archivos").performClick()
        robot.pickMultiple(first, second)
        compose.awaitSources()
        compose.onNodeWithText(second).assertIsDisplayed()
        robot.assertReadOnlyGrants(2)
        compose.onNodeWithText("Elegir carpeta").performClick()
        robot.pickFolder()
        compose.awaitSources()
        compose.onNodeWithText(robot.folderName).assertIsDisplayed()
        robot.assertReadOnlyGrants(3)

        compose.onNodeWithText("Elegir archivos").performClick()
        robot.back()
        compose.awaitSources()
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.onNodeWithText(second).assertIsDisplayed()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        compose.onNode(hasText("Explorador") and hasClickAction()).assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText(first).assertIsDisplayed()
        openSelection()
        compose.onNodeWithContentDescription("Quitar ${robot.folderName}").performScrollTo().performClick()
        robot.assertReadOnlyGrants(2)
        compose.onNodeWithContentDescription("Quitar $first").performScrollTo().performClick()
        robot.assertReadOnlyGrants(1)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = SourceStore(context).restore().sources.single()
        assertEquals(second, source.metadata?.name)
        assertTrue(source.available)
        // Removing a selection must leave the original fixture in its provider.
        val original = context.contentResolver.query(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(first), null
        )!!.use { assertTrue(it.moveToFirst()); it.getLong(0) }
        assertTrue(original > 0)

        compose.onNodeWithContentDescription("Quitar $second").performScrollTo().performClick()
        robot.assertReadOnlyGrants(0)
        compose.awaitSources()
        compose.onNodeWithText("Listo").assertIsEnabled().performClick()
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
    }

    @Test
    fun revokedGrantAndDeletedDocumentDoNotRemoveOtherSources() {
        robot.createFile(first)
        robot.createFile(second)
        openSelection()
        compose.onNodeWithText("Elegir archivos").performClick()
        robot.pickMultiple(first, second)
        compose.awaitSources()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = SourceStore(context).restore().sources
        val revoked = sources.first { it.metadata?.name == first }
        context.contentResolver.releasePersistableUriPermission(Uri.parse(revoked.record.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText(second).assertIsDisplayed()
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        val restored = SourceStore(context).restore().sources
        assertEquals(2, restored.size)
        assertFalse(restored.first { it.record.uri == revoked.record.uri }.available)
        assertTrue(restored.first { it.record.uri != revoked.record.uri }.available)

        robot.deleteFile(second)
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        assertEquals(2, SourceStore(context).restore().sources.size)
        openSelection()
        compose.onNodeWithText(first).assertIsDisplayed()
        compose.onNodeWithText(second).assertIsDisplayed()
        compose.onNodeWithText("Listo").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
    }

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
    fun rejectionMessageSurvivesTheRefreshOnPickerReturn() {
        lateinit var model: SourcesViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[SourcesViewModel::class.java]
            model.add(
                listOf("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTP1-m2"),
                SourceKind.Tree, 0
            )
            model.refresh()
        }
        compose.waitUntil(10_000) { !model.state.value.busy && model.state.value.message == R.string.source_could_not_save }
        compose.onNodeWithText(compose.activity.getString(R.string.source_could_not_save)).assertIsDisplayed()
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        openSelection()
        compose.onNodeWithText(compose.activity.getString(R.string.source_could_not_save)).assertIsDisplayed()
        compose.onNodeWithText("Listo").assertIsEnabled()
    }

    @Test
    fun successfulRetryClearsRestorationError() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "sources.json")
        file.writeText("broken JSON")
        lateinit var model: SourcesViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[SourcesViewModel::class.java]
            model.refresh()
        }
        compose.waitUntil(10_000) { !model.state.value.busy && !model.state.value.storageReady }
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        openSelection()
        compose.onNodeWithText("Reintentar").assertIsDisplayed()
        file.writeText("[]")
        compose.onNodeWithText("Reintentar").performClick()
        compose.awaitSources()
        assertTrue(model.state.value.storageReady)
        assertNull(model.state.value.message)
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
    }

    @Test
    fun movedFileRemainsAccessibleInSelectedFolderWithoutGlobalWarningOrForgettingSelection() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val png = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        robot.createFile("Captura.png", mime = "image/png", bytes = png)
        robot.createFile("Aleatorio.txt")
        robot.createFile("Dentro.txt", directory = "Documentos")
        openSelection()
        compose.onNodeWithText("Elegir carpeta").performClick()
        robot.pickFolder("Documentos")
        compose.awaitSources()
        for (name in listOf("Captura.png", "Aleatorio.txt")) {
            compose.onNodeWithText("Elegir archivos").performClick()
            robot.pickStorageDocument(name)
            compose.awaitSources()
        }
        robot.assertReadOnlyGrants(3)
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        lateinit var model: SourcesViewModel
        lateinit var browser: BrowserViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[SourcesViewModel::class.java]
            browser = ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
        }
        val before = model.state.value.sources.single { it.metadata?.name == "Captura.png" }.record
        assertEquals("com.android.externalstorage.documents", before.uri.toUri().authority)
        // External fixture mutation, performed by the test shell rather than TP1's read-only grants.
        shell("mv /sdcard/Download/${robot.folderName}/Captura.png /sdcard/Download/${robot.folderName}/Documentos/Captura.png")
        compose.runOnIdle { model.refresh() }
        compose.awaitSources()
        compose.waitUntil(10_000) { browser.state.value.entries.size == 2 }
        assertEquals(setOf("Documentos", "Aleatorio.txt"), browser.state.value.entries.map { it.metadata.name }.toSet())
        assertNull(model.state.value.message)
        assertEquals(3, model.state.value.sources.size)
        assertFalse(model.state.value.sources.single { it.record.uri == before.uri }.available)
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        compose.onNodeWithText("Captura.png").assertDoesNotExist()
        capture("roots-after-move")

        compose.onNodeWithText("Documentos").performClick()
        compose.waitUntil(10_000) { !browser.state.value.loading && browser.state.value.entries.size == 2 }
        compose.onNodeWithText("Captura.png").assertIsDisplayed()
        val moved = browser.state.value.entries.single { it.metadata.name == "Captura.png" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.contentResolver.openInputStream(moved.uri.toUri())!!.use { assertEquals(0x89, it.read()) }
        assertNotEquals(before.uri, moved.uri)
        capture("moved-file-in-folder")

        openSelection()
        compose.onNodeWithText("No disponible").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Quitar Captura.png").performScrollTo().assertIsEnabled()
        capture("unavailable-selection")
        compose.onNodeWithText("Reintentar").performScrollTo().performClick()
        compose.awaitSources()
        assertEquals(3, SourceStore(context).restore().sources.size)
        robot.assertReadOnlyGrants(3)
        compose.onNodeWithText("Listo").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Captura.png").assertIsDisplayed()
        assertNull(model.state.value.message)
    }

    @Test
    fun temporaryProviderFailureKeepsGrantAndSelectionAndRetryRecoversWithoutReselection() {
        val uri = fixtureCall("fixture-authorize", "file").getString("uri")!!
        lateinit var model: SourcesViewModel
        openSelection()
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[SourcesViewModel::class.java]
            model.add(listOf(uri), SourceKind.Document,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        compose.awaitSources()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        compose.onNodeWithText("file").assertIsDisplayed()
        fixtureCall("fixture-file-unavailable", "true")
        compose.runOnIdle { model.refresh() }
        compose.awaitSources()
        assertFalse(model.state.value.sources.single().available)
        assertEquals(uri, model.state.value.sources.single().record.uri)
        assertNull(model.state.value.message)
        robot.assertReadOnlyGrants(1)
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        openSelection()
        compose.onNodeWithText("No disponible").assertIsDisplayed()
        capture("temporary-provider-failure")
        compose.onNodeWithText("Listo").performScrollTo().assertIsEnabled()
        fixtureCall("fixture-file-unavailable", "false")
        compose.onNodeWithText("Reintentar").performScrollTo().performClick()
        compose.awaitSources()
        assertTrue(model.state.value.sources.single().available)
        assertEquals(uri, model.state.value.sources.single().record.uri)
        assertNull(model.state.value.message)
        compose.onNodeWithText("No disponible").assertDoesNotExist()
        compose.onNodeWithText("Reintentar").assertDoesNotExist()
        robot.assertReadOnlyGrants(1)
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        compose.onNodeWithText("file").assertIsDisplayed()
        capture("provider-recovered")
    }
}

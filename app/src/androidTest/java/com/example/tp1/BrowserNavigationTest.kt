package com.example.tp1

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.browser.BrowserProblem
import com.example.tp1.browser.BrowserViewModel
import com.example.tp1.browser.DocumentBrowser
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourcesViewModel
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BrowserNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val robot = PickerRobot()
    private lateinit var browser: BrowserViewModel
    private lateinit var sources: SourcesViewModel

    @Before fun prepare() {
        robot.resetSources()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.runOnIdle {
            browser = ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
            sources = ViewModelProvider(compose.activity)[SourcesViewModel::class.java]
        }
    }

    @After fun cleanUp() {
        robot.cleanUp()
        shell("rm -rf /sdcard/Download/${robot.folderName}")
    }

    private fun awaitBrowser(depth: Int) {
        compose.awaitSources()
        compose.waitUntil(15_000) { browser.state.value.path.size == depth && !browser.state.value.loading && !browser.state.value.opening }
        compose.waitForIdle()
    }

    private fun enter(name: String, depth: Int) {
        tapRow(name)
        awaitBrowser(depth)
    }

    private fun safeRow(name: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        val row = compose.onNodeWithText(name).performScrollTo()
        val tabTop = compose.onNode(hasText("Chat") and hasClickAction()).getUnclippedBoundsInRoot().top
        // performScrollTo uses the edge-to-edge list viewport, which extends behind the native tab bar.
        if (row.getUnclippedBoundsInRoot().bottom > tabTop) {
            compose.onNodeWithTag("browser-list").performTouchInput { swipeUp(startY = height * 0.6f, endY = height * 0.25f) }
        }
        return row
    }

    private fun tapRow(name: String) { safeRow(name).performClick() }

    private fun back(depth: Int) {
        androidx.test.espresso.Espresso.pressBack()
        awaitBrowser(depth)
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command)
        .let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() } }

    private fun capture(name: String) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/TP1-m3-evidence-final/")
        })!!
        context.contentResolver.openOutputStream(uri)!!.use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()
    }

    private fun image(): ByteArray {
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.rgb(248, 249, 255))
            drawCircle(400f, 230f, 125f, Paint().apply { color = Color.rgb(0, 90, 193) })
            drawText("TP1 · Milestone 3", 110f, 440f, Paint().apply { color = Color.BLACK; textSize = 60f })
            drawText("Archivo local autorizado", 105f, 510f, Paint().apply { color = Color.DKGRAY; textSize = 42f })
        }
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun awaitPhoto() {
        robot.awaitPackage { it == "com.google.android.apps.photos" }
        // A foreground viewer alone is not proof that it decoded the authorized PNG.
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) {
            val frame = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            var blue = 0
            for (y in frame.height / 5 until frame.height * 4 / 5 step 8) {
                for (x in 0 until frame.width step 8) {
                    val pixel = frame.getPixel(x, y)
                    if (Color.red(pixel) < 30 && Color.green(pixel) in 70..130 && Color.blue(pixel) > 170) blue++
                }
            }
            frame.recycle()
            if (blue > 200) return
            SystemClock.sleep(300)
        }
        capture("viewer-render-failure")
        error("Google Photos did not render the fixture's blue circle: ${robot.visibleText()}")
    }

    private fun captureLargeText(longName: String) {
        val size = shell("wm size")
        val density = shell("wm density")
        val font = shell("settings get system font_scale").trim()
        try {
            shell("wm size 900x2000")
            shell("wm density 450")
            shell("settings put system font_scale 2.0")
            SystemClock.sleep(1500)
            awaitBrowser(3)
            compose.onNodeWithText("Añadir").assertIsDisplayed()
            capture("nested-large-text-320dp-top")
            safeRow(longName).assertIsDisplayed()
            capture("nested-large-text-320dp-files")
        } finally {
            shell("settings put system font_scale $font")
            shell(if (size.contains("Override")) "wm size ${size.trim().substringAfterLast(":").trim()}" else "wm size reset")
            shell(if (density.contains("Override")) "wm density ${density.trim().substringAfterLast(":").trim()}" else "wm density reset")
        }
        SystemClock.sleep(800)
        awaitBrowser(3)
    }

    @Test fun realTreeBrowsingViewerRoundTripsAndAccessRecovery() {
        val nested = "Nivel1/Nivel2"
        val longName = "Contrato_de_alquiler_firmado_y_revisado_octubre_2026.png"
        val png = image()
        val independent = robot.createFile("Independiente.png", mime = "image/png", bytes = png)
        robot.createFile("Prueba.png", nested, "image/png", png)
        robot.createFile(longName, nested, "image/png", png)
        robot.createFile("Permiso.txt", nested, bytes = "TP1 viewer grant proof".toByteArray())
        robot.createFile("Sin_visor.tp1noviewer", nested, "application/x-tp1-no-viewer")
        shell("mkdir -p /sdcard/Download/${robot.folderName}/$nested/Vacia")
        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithText("Elegir carpeta").performClick()
        robot.pickFolder()
        compose.awaitSources()
        compose.onNodeWithText("Elegir archivos").performClick()
        robot.pickDocument("Independiente.png")
        compose.awaitSources()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        compose.waitUntil(10_000) { browser.state.value.entries.size == 2 }
        capture("authorized-roots")

        compose.onNodeWithText("Independiente.png").performClick()
        awaitPhoto()
        capture("viewer-independent-image")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        robot.awaitPackage { it == context.packageName }
        awaitBrowser(0)

        enter(robot.folderName, 1)
        assertEquals(setOf("Nivel1", "Independiente.png"), browser.state.value.entries.map { it.metadata.name }.toSet())
        capture("tree-root")
        enter("Nivel1", 2)
        enter("Nivel2", 3)
        val position = browser.state.value.path
        assertEquals(setOf("Prueba.png", longName, "Permiso.txt", "Sin_visor.tp1noviewer", "Vacia"), browser.state.value.entries.map { it.metadata.name }.toSet())
        capture("nested-level-two")
        captureLargeText(longName)
        enter("Vacia", 4)
        compose.onNodeWithText("Carpeta vacía").assertIsDisplayed()
        compose.onNodeWithText("Pulsa Añadir para elegir archivos o carpetas.").assertDoesNotExist()
        capture("empty-folder")
        back(3)

        compose.onNode(hasText("Chat") and hasClickAction()).performClick().assertIsSelected()
        compose.runOnIdle { assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        assertEquals(position, browser.state.value.path)
        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        assertEquals(position, browser.state.value.path)
        compose.onNodeWithText("Añadir").performClick()
        androidx.test.espresso.Espresso.pressBack()
        assertEquals(position, browser.state.value.path)
        compose.activityRule.scenario.recreate()
        awaitBrowser(3)
        assertEquals(position, browser.state.value.path)

        tapRow("Permiso.txt")
        robot.awaitPackage { it == "android" || it == "com.example.tp1.test" }
        if (robot.visibleText().contains("Open with")) {
            robot.click("com.example.tp1.test")
            robot.click("Always")
        }
        robot.awaitPackage { it == "com.example.tp1.test" }
        assertTrue(robot.visibleText(), robot.visibleText().contains("TP1 viewer proof: read OK; write denied; root denied; sibling denied; MIME OK; ClipData OK"))
        android.util.Log.i("TP1-M3", robot.visibleText())
        capture("viewer-read-only-grant-proof")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        robot.awaitPackage { it == context.packageName }
        awaitBrowser(3)
        // A second tap must honor Android's chosen default without forcing another chooser.
        tapRow("Permiso.txt")
        robot.awaitPackage { it == "com.example.tp1.test" }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        robot.awaitPackage { it == context.packageName }
        awaitBrowser(3)

        tapRow("Prueba.png")
        awaitPhoto()
        capture("viewer-nested-image")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        robot.awaitPackage { it == context.packageName }
        awaitBrowser(3)
        assertEquals(position, browser.state.value.path)
        capture("browser-after-viewer-return")

        val unknown = browser.state.value.entries.single { it.metadata.name == "Sin_visor.tp1noviewer" }
        val treeSource = sources.state.value.sources.single { it.record.kind == SourceKind.Tree }
        assertEquals("application/octet-stream", DocumentBrowser(context.contentResolver).viewIntent(treeSource, unknown).type)

        robot.deleteFile("Permiso.txt", nested)
        tapRow("Permiso.txt")
        compose.waitUntil(10_000) { !browser.state.value.opening && browser.state.value.message != null }
        assertEquals("Deleted file", BrowserProblem.Missing, browser.state.value.message)
        capture("missing-file")
        compose.onNodeWithText("Reintentar").performClick()
        awaitBrowser(3)
        assertFalse(browser.state.value.entries.any { it.metadata.name == "Permiso.txt" })

        // Breadcrumbs jump only through this root's visited path.
        compose.onNodeWithText("Nivel1").performScrollTo().performClick()
        awaitBrowser(2)
        enter("Nivel2", 3)
        shell("rm -rf /sdcard/Download/${robot.folderName}/$nested")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(15_000) { browser.state.value.problem == BrowserProblem.Missing }
        capture("missing-current-folder")
        back(2)

        val tree = sources.state.value.sources.single { it.record.kind == SourceKind.Tree }
        context.contentResolver.releasePersistableUriPermission(tree.record.uri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        compose.runOnIdle { sources.refresh() }
        compose.waitUntil(15_000) { browser.state.value.atRoots && browser.state.value.entries.size == 1 }
        compose.onNodeWithText("Añadir archivos").assertDoesNotExist()
        compose.onNodeWithText("Independiente.png").assertIsDisplayed()
        capture("revoked-access-keeps-healthy-source")

        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithText("Elegir carpeta").performClick()
        robot.pickFolder()
        compose.awaitSources()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        enter(robot.folderName, 1)
        enter("Nivel1", 2)
        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithContentDescription("Quitar ${robot.folderName}").performScrollTo().performClick()
        compose.awaitSources()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        awaitBrowser(0)
        assertEquals(listOf("Independiente.png"), browser.state.value.entries.map { it.metadata.name })
        context.contentResolver.openInputStream(independent)!!.use { input ->
            assertEquals(0x89, input.read())
            assertEquals('P'.code, input.read())
        }
        robot.assertReadOnlyGrants(1)
        capture("current-root-removal")
    }

    @Test fun providerFailuresNullableMetadataAndStaleLoadsStayInScope() {
        val resolver = context.contentResolver
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        val uri = try {
            resolver.call("content://com.example.tp1.test.documents".toUri(), "fixture-authorize", null, null)!!.getString("uri")!!
        } finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        compose.runOnIdle { sources.add(listOf(uri), SourceKind.Tree,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) }
        compose.awaitSources()
        compose.waitUntil(10_000) { browser.state.value.entries.size == 1 }
        enter("root", 1)
        assertEquals(9, browser.state.value.entries.size)
        assertTrue(browser.state.value.entries.all { it.metadata.size == null && it.metadata.modified == null })
        fun visits(): List<String> {
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
            return try { resolver.call(uri.toUri(), "fixture-visits", null, null)!!.getStringArrayList("visits")!! }
            finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }
        }
        assertEquals(listOf("root"), visits())
        tapRow("slow")
        compose.waitUntil(5_000) { browser.state.value.loading && browser.state.value.path.size == 2 }
        back(1)
        SystemClock.sleep(1600)
        assertEquals("root", browser.state.value.path.single().name)
        assertEquals(9, browser.state.value.entries.size)
        assertTrue(visits().all { it == "root" || it == "slow" })
        for (name in listOf("error", "null", "missing-id")) {
            enter(name, 2)
            assertEquals(BrowserProblem.Provider, browser.state.value.problem)
            compose.onNodeWithText("Carpeta vacía").assertDoesNotExist()
            compose.onNodeWithText("Reintentar").performScrollTo().assertIsDisplayed()
            if (name == "error") capture("provider-error")
            back(1)
        }
        enter("pending", 2)
        assertTrue(browser.state.value.pending)
        compose.onNodeWithText("Cargando carpeta…").assertIsDisplayed()
        capture("provider-loading")
        back(1)
        enter("gone", 2)
        assertEquals(BrowserProblem.Missing, browser.state.value.problem)
        back(1)
        val source = sources.state.value.sources.single()
        val file = browser.state.value.entries.single { it.metadata.name == "file" }
        val intent = DocumentBrowser(resolver).viewIntent(source, file)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(file.uri, intent.data.toString())
        assertEquals("application/x-tp1-probe", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
        assertEquals(intent.data, intent.clipData!!.getItemAt(0).uri)
        assertThrows(SecurityException::class.java) {
            DocumentBrowser(resolver).viewIntent(source, file.copy(uri = "content://other/document/file"))
        }
        tapRow("unviewable")
        compose.waitUntil(10_000) { !browser.state.value.opening && browser.state.value.message != null }
        assertEquals(browser.state.value.toString(), BrowserProblem.NoViewer, browser.state.value.message)
        compose.onNodeWithText(context.getString(R.string.browser_no_viewer)).assertIsDisplayed()
        capture("missing-viewer")
        compose.runOnIdle { browser.reload() }
        awaitBrowser(1)
        val lastRow = safeRow("sibling")
        val before = lastRow.getUnclippedBoundsInRoot().top
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        lastRow.assertIsDisplayed()
        assertEquals(before, lastRow.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("Añadir").performClick()
        androidx.test.espresso.Espresso.pressBack()
        lastRow.assertIsDisplayed()
        assertEquals(before, lastRow.getUnclippedBoundsInRoot().top)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitBrowser(1)
        lastRow.assertIsDisplayed()
        assertEquals(before, lastRow.getUnclippedBoundsInRoot().top)
        compose.onNodeWithText("Inicio").performScrollTo().performClick()
        awaitBrowser(0)
        assertEquals(1, browser.state.value.entries.size)
        assertFalse(compose.activity.isFinishing)
    }
}

package com.example.tp1

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.lifecycle.ViewModelProvider
import com.example.tp1.sources.SourcesViewModel

internal fun AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>.awaitSources() {
    lateinit var model: SourcesViewModel
    runOnIdle { model = ViewModelProvider(activity)[SourcesViewModel::class.java] }
    waitUntil(15_000) { !model.state.value.busy && !model.state.value.restoring }
}

/** Drives the actual DocumentsUI picker. English labels belong to the test device's OS. */
internal class PickerRobot {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private val fixtures = mutableMapOf<String, Uri>()
    val folderName = "TP1-m2-${SystemClock.elapsedRealtimeNanos()}"

    fun resetSources() {
        context.contentResolver.persistedUriPermissions.forEach {
            context.contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        listOf("sources.json", "sources.json.bak", "sources.json.new").forEach(context::deleteFile)
    }

    fun createFile(
        name: String, directory: String = "", mime: String = "text/plain",
        bytes: ByteArray = "TP1 source selection fixture\n".toByteArray()
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/$folderName/$directory/")
        }
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        fixtures["$directory/$name"] = uri
        return uri
    }

    fun cleanUp() {
        resetSources()
        fixtures.values.forEach { context.contentResolver.delete(it, null, null) }
    }

    fun deleteFile(name: String, directory: String = "") {
        context.contentResolver.delete(checkNotNull(fixtures["$directory/$name"]), null, null)
    }

    fun pickDocument(name: String) {
        openDownloads()
        click(folderName, documentItem = true)
        click(name, documentItem = true)
        awaitApp()
    }

    fun pickMultiple(first: String, second: String) {
        openDownloads()
        click(folderName, documentItem = true)
        click(first, longClick = true, documentItem = true)
        click(second, documentItem = true)
        click("Select", alternative = "Open")
        awaitApp()
    }

    private fun openStorageFixture() {
        awaitPackage { it.endsWith(".documentsui") }
        openProviderRoot(android.os.Build.MODEL)
        click("Download", documentItem = true)
        click(folderName, documentItem = true)
    }

    fun pickStorageDocument(name: String) {
        openStorageFixture()
        click(name, documentItem = true)
        awaitApp()
    }

    fun pickFolder(child: String? = null) {
        openStorageFixture()
        child?.let { click(it, documentItem = true) }
        click("Use this folder")
        click("Allow")
        awaitApp()
    }

    private fun openDownloads() {
        awaitPackage { it.endsWith(".documentsui") }
        openProviderRoot("Downloads")
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        automation.clearCache()
        return automation.rootInActiveWindow
    }

    private fun rootsList(): AccessibilityNodeInfo? = flatten(activeRoot()).firstOrNull {
        it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true
    }

    private fun openProviderRoot(label: String) {
        if (rootsList() == null) click("Show roots")
        click(label, inRoots = true)
    }

    fun back() {
        // A remembered picker stack may consume Back before cancelling. Never send Back after TP1 returns.
        openDownloads()
        repeat(4) {
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            val deadline = SystemClock.uptimeMillis() + 2_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (activeRoot()?.packageName?.toString() == context.packageName) return
                SystemClock.sleep(100)
            }
            if (activeRoot()?.packageName?.toString()?.endsWith(".documentsui") != true) {
                awaitApp()
                return
            }
        }
        awaitApp()
    }

    private fun awaitApp() = awaitPackage { it == context.packageName }

    fun visibleText(): String = flatten(activeRoot()).mapNotNull { it.text }.joinToString("\n")

    fun awaitPackage(matches: (String) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (matches(activeRoot()?.packageName?.toString().orEmpty())) {
                SystemClock.sleep(300)
                return
            }
            SystemClock.sleep(100)
        }
        error("Unexpected active window: ${flatten(activeRoot()).mapNotNull { it.text ?: it.contentDescription }}")
    }

    fun click(label: String, longClick: Boolean = false, alternative: String? = null,
        inRoots: Boolean = false, documentItem: Boolean = false) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        val action = if (longClick) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        while (SystemClock.uptimeMillis() < deadline) {
            val nodes = flatten(if (inRoots) rootsList() else activeRoot())
            val matches = nodes.filter {
                it.isEnabled && it.isVisibleToUser && (!documentItem || generateSequence(it) { node -> node.parent }
                    .any { node -> node.viewIdResourceName?.endsWith(":id/item_root") == true }) &&
                    (it.text?.toString().equals(label, ignoreCase = true) ||
                    it.contentDescription?.toString().equals(label, ignoreCase = true) ||
                    (alternative != null && it.text?.toString().equals(alternative, ignoreCase = true)))
            }
            for (match in matches.asReversed()) {
                var node: AccessibilityNodeInfo? = match
                while (node != null) {
                    if (node.actionList.any { it.id == action } && node.performAction(action)) {
                        SystemClock.sleep(500)
                        try { automation.waitForIdle(300, 5_000) } catch (_: java.util.concurrent.TimeoutException) { }
                        return
                    }
                    node = node.parent
                }
            }
            SystemClock.sleep(150)
        }
        error("Picker control '$label' missing: ${flatten(activeRoot()).mapNotNull { it.text ?: it.contentDescription }}")
    }

    fun assertReadOnlyGrants(count: Int) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (context.contentResolver.persistedUriPermissions.size != count && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(100)
        }
        val grants = context.contentResolver.persistedUriPermissions
        assertTrue("Expected $count retained grants, found $grants", grants.size == count)
        assertTrue(grants.all { it.isReadPermission && !it.isWritePermission })
    }

    private fun flatten(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { flatten(node.getChild(it)) }
}

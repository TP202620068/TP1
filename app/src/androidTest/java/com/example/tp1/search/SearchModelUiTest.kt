package com.example.tp1.search

import android.view.KeyEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.tp1.MainActivity
import com.example.tp1.OfflineModelAcquisitionRule
import com.example.tp1.PickerRobot
import com.example.tp1.awaitSources
import com.example.tp1.browser.BrowserViewModel
import com.example.tp1.indexing.IndexingCoordinator
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/** Production automatic indexing, Jina/SQLite search and an external read-only viewer. No Chat generation. */
@ManualInference
class SearchModelUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val picker = PickerRobot()
    @get:Rule(order = 0) val offlineAcquisition = OfflineModelAcquisitionRule()
    @get:Rule(order = 1) val productionIndexing = object : ExternalResource() {
        override fun before() = runBlocking {
            check(ModelManager(context).isAvailable(Model.JinaV5NanoRetrieval)) { "Provision the pinned local Jina model first" }
            IndexingCoordinator.replaceForTests(null)?.close()
            Unit
        }
        override fun after() = runBlocking { IndexingCoordinator.replaceForTests(null)?.close(); Unit }
    }
    @get:Rule(order = 2) val compose = createAndroidComposeRule<MainActivity>()
    @After fun cleanup() = picker.cleanUp()

    @Test fun searchesOutsideCurrentFolderAndOpensOriginalWithReadOnlyGrant() {
        picker.resetSources()
        picker.createFile("warranty.rtf", "contracts", "application/rtf",
            "{\\rtf1\\ansi TP1 grounded viewer proof. Warranty coverage lasts two years. Keep the receipt for repairs.}".toByteArray())
        picker.createFile("gardening.rtf", "garden", "application/rtf",
            "{\\rtf1\\ansi Tomatoes need sunlight and regular watering.}".toByteArray())
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithText("Elegir carpeta").performClick()
        picker.pickFolder()
        compose.awaitSources()
        val indexing = IndexingCoordinator.get(context)
        compose.waitUntil(120_000) {
            val state = indexing.state.value
            check(state.blocked == null) { "Automatic indexing blocked: ${state.blocked}" }
            state.sources.values.any { !it.discovering && it.pending == 0 && it.completed == 2 }
        }
        assertTrue(indexing.state.value.sources.values.all { it.failures.isEmpty() })
        compose.onNodeWithText("Listo").performClick()
        val browser = ViewModelProvider(compose.activity)[BrowserViewModel::class.java]
        compose.onNodeWithText(picker.folderName).performClick()
        compose.waitUntil(10_000) { browser.state.value.path.size == 1 && !browser.state.value.loading }
        compose.onNodeWithText("garden").performClick()
        compose.waitUntil(10_000) { browser.state.value.path.size == 2 && !browser.state.value.loading }
        val location = browser.state.value.path
        compose.onNodeWithText("gardening.rtf").assertIsDisplayed()
        val search = ViewModelProvider(compose.activity)[SearchViewModel::class.java]
        compose.onNodeWithContentDescription("Buscar contenido").performTextInput("How long is the warranty coverage?")
        compose.onNodeWithContentDescription("Buscar en documentos").performClick()
        compose.waitUntil(60_000) { search.state.value.phase == SearchPhase.Idle && search.state.value.showingResults }
        assertNull(search.state.value.problem)
        assertEquals("warranty.rtf", search.state.value.rows.first().document.name)
        assertTrue(search.state.value.rows.first().excerpt.contains("two years"))
        assertEquals(location, browser.state.value.path)
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        assertSame(search, ViewModelProvider(compose.activity)[SearchViewModel::class.java])
        compose.onNodeWithText("warranty.rtf").performClick()
        picker.awaitPackage { it == instrumentation.context.packageName }
        val proof = "TP1 grounded viewer proof: read OK; write denied; root denied; sibling denied; MIME OK; ClipData OK; uid=" +
            instrumentation.context.applicationInfo.uid
        compose.waitUntil(10_000) { picker.visibleText().contains(proof) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        picker.awaitPackage { it == context.packageName }
        compose.waitUntil(10_000) { search.state.value.phase == SearchPhase.Idle }
        compose.onNodeWithText("warranty.rtf").assertIsDisplayed()
        compose.onNodeWithContentDescription("Limpiar búsqueda").performClick()
        compose.onNodeWithText("gardening.rtf").assertIsDisplayed()
        assertEquals(location, browser.state.value.path)
    }
}

package com.example.tp1.search

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.tp1.browser.BrowserFolder
import com.example.tp1.browser.BrowserProblem
import com.example.tp1.browser.BrowserState
import com.example.tp1.browser.DocumentReference
import com.example.tp1.indexing.IndexingState
import com.example.tp1.indexing.SourceIndexingState
import com.example.tp1.ui.TP1App
import com.example.tp1.ui.components.FileKind
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.theme.TP1Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SearchScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun row() = SearchRow(DocumentReference("fixture", "a", "Warranty.rtf", emptyList()), "Warranty lasts two years.")

    @Test fun loadingEmptyErrorsAndExcerptsAreDistinctAndIndexingRemainsVisible() {
        var state by mutableStateOf(SearchState(draft = "warranty", submitted = "warranty", phase = SearchPhase.Searching))
        var retries = 0
        compose.setContent { TP1Theme {
            TP1App(search = state, onSearchRetry = { retries++ }, indexing = IndexingState(mapOf(
                "source" to SourceIndexingState(discovering = false, pending = 1, completed = 1))))
        } }
        compose.onNodeWithText("Buscando en documentos indexados…").assertIsDisplayed()
        compose.onNodeWithText("Indexando documentos 1/2").assertIsDisplayed()
        compose.onNodeWithText("Sin archivos").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(phase = SearchPhase.Idle) }
        compose.onNodeWithText("Sin resultados").assertIsDisplayed()
        for (problem in SearchProblem.entries) {
            compose.runOnIdle { state = state.copy(problem = problem) }
            compose.onNodeWithText("Sin resultados").assertDoesNotExist()
            if (problem != SearchProblem.QueryLimit) compose.onNodeWithText("Reintentar").performClick()
        }
        compose.runOnIdle { assertEquals(4, retries); state = state.copy(problem = null, rows = listOf(row())) }
        compose.onNodeWithText("Warranty.rtf").assertIsDisplayed()
        compose.onNodeWithText("Warranty lasts two years.").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(phase = SearchPhase.Checking) }
        compose.onNodeWithText("Warranty lasts two years.").assertDoesNotExist()
        compose.onNodeWithText("Verificando el acceso a los resultados…").assertIsDisplayed()
    }

    @Test fun clearingResultsRestoresIndependentFolderScrollAndLocation() {
        var state by mutableStateOf(SearchState())
        val files = (0..40).map { FileListItem("File $it", FileKind.Document, id = "$it") }
        val browser = BrowserState(sourceUri = "source", path = listOf(BrowserFolder("nested", "Nested")))
        compose.setContent { TP1Theme {
            TP1App(search = state, files = files, browser = browser, onSearchClear = { state = SearchState() })
        } }
        compose.onNodeWithTag("browser-list").performScrollToIndex(25)
        val before = compose.onNodeWithText("File 23").getUnclippedBoundsInRoot()
        compose.runOnIdle { state = SearchState(draft = "warranty", submitted = "warranty", rows = listOf(row())) }
        compose.onNodeWithText("Warranty.rtf").assertIsDisplayed()
        compose.onNodeWithContentDescription("Limpiar búsqueda").performClick()
        assertEquals(before, compose.onNodeWithText("File 23").getUnclippedBoundsInRoot())
        assertEquals("nested", browser.path.single().uri)
    }

    @Test fun largeTextControlsAreAccessibleAndOpeningErrorsStayInExplorer() {
        var state by mutableStateOf(SearchState(draft = "warranty", submitted = "warranty", rows = listOf(row())))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TP1Theme { TP1App(search = state, onSearchOpenDocument = { state = state.copy(openProblem = BrowserProblem.NoViewer) }) }
            }
        }
        val button = compose.onNodeWithContentDescription("Buscar en documentos")
        button.assertIsDisplayed()
        val bounds = button.getUnclippedBoundsInRoot()
        assertTrue(bounds.bottom - bounds.top >= 48.dp)
        compose.onNodeWithContentDescription("Limpiar búsqueda").assertIsDisplayed()
        compose.onNodeWithText("Warranty.rtf").performClick()
        compose.onNodeWithText("No hay una aplicación compatible para abrir este archivo.").assertIsDisplayed()
    }

    @Test fun resultScrollSurvivesSavedUiStateRestorationWithoutSavingPassagesThere() {
        val restore = StateRestorationTester(compose)
        val rows = (0..4).map { row().copy(document = row().document.copy(providerId = "$it", name = "Result $it"),
            excerpt = "Text ".repeat(50)) }
        restore.setContent { TP1Theme { TP1App(search = SearchState(draft = "q", submitted = "q", rows = rows)) } }
        compose.onNodeWithTag("search-list").performScrollToIndex(4)
        val before = compose.onNodeWithText("Result 2").getUnclippedBoundsInRoot()
        restore.emulateSavedInstanceStateRestore()
        assertEquals(before, compose.onNodeWithText("Result 2").getUnclippedBoundsInRoot())
    }
}

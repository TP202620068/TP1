package com.example.tp1.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import com.example.tp1.MainActivity
import com.example.tp1.OfflineChatRule
import com.example.tp1.PickerRobot
import com.example.tp1.awaitSources
import com.example.tp1.indexing.RetrievalResult
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SearchNavigationTest {
    private val queries = mutableListOf<String>()
    private var pending = false
    @get:Rule(order = 0) val offline = OfflineChatRule(searchRetrieve = {
        queries += it
        if (pending) awaitCancellation()
        RetrievalResult.Complete(listOf(SearchViewModelTest.match(text = "Warranty lasts two years.")))
    })
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val picker = PickerRobot()
    private lateinit var search: SearchViewModel

    @Before fun start() {
        picker.resetSources()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.runOnIdle { search = ViewModelProvider(compose.activity)[SearchViewModel::class.java] }
    }
    @After fun cleanup() = picker.cleanUp()

    @Test fun imeAndButtonSubmitOnlyOnceAndResultsSurviveTabsAndActivityRecreation() {
        val field = compose.onNodeWithContentDescription("Buscar contenido")
        field.performTextInput("  warranty  ")
        compose.runOnIdle { assertTrue(queries.isEmpty()) }
        field.performImeAction()
        compose.onNodeWithText("Warranty lasts two years.").assertIsDisplayed()
        field.performTextReplacement("new draft")
        compose.onNodeWithText("Resultados para: warranty").assertIsDisplayed()
        compose.runOnIdle { assertEquals(listOf("warranty"), queries) }
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { search.state.value.phase == SearchPhase.Idle }
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.runOnIdle { assertSame(search, ViewModelProvider(compose.activity)[SearchViewModel::class.java]) }
        compose.onNodeWithText("Warranty lasts two years.").assertIsDisplayed()
        field.assertTextEquals("new draft")
        compose.runOnIdle { assertEquals(1, queries.size) }
        compose.onNodeWithContentDescription("Buscar en documentos").performClick()
        compose.runOnIdle { assertEquals(listOf("warranty", "new draft"), queries) }
        compose.onNodeWithContentDescription("Limpiar búsqueda").performClick()
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun tabSourceSelectionAndBackgroundCancelWithoutAutomaticResubmission() {
        pending = true
        compose.onNodeWithContentDescription("Buscar contenido").performTextInput("captured")
        compose.onNodeWithContentDescription("Buscar en documentos").performClick()
        compose.onNodeWithText("Buscando en documentos indexados…").assertIsDisplayed()
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        compose.onNodeWithText("La búsqueda se canceló al salir del Explorador. Pulsa Reintentar para volver a buscar.").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, queries.size) }
        compose.onNodeWithText("Reintentar").performClick()
        compose.onNodeWithText("Añadir").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.runOnIdle { assertEquals(SearchProblem.Interrupted, search.state.value.problem); assertEquals(2, queries.size) }
        compose.onNodeWithText("Reintentar").performClick()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.awaitSources()
        compose.runOnIdle { assertEquals(SearchProblem.Interrupted, search.state.value.problem); assertEquals(3, queries.size) }
    }

    @Test fun backDismissesKeyboardThenResultsBeforeLeavingExplorer() {
        val field = compose.onNodeWithContentDescription("Buscar contenido")
        field.performTextInput("warranty")
        field.performImeAction()
        compose.onNodeWithText("Warranty lasts two years.").assertIsDisplayed()
        field.performClick()
        compose.waitUntil(5_000) { keyboardVisible() }
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { !keyboardVisible() }
        compose.runOnIdle { assertTrue(search.state.value.showingResults) }
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        field.assertTextEquals("warranty")
        compose.runOnIdle { assertFalse(search.state.value.showingResults); assertEquals(1, queries.size) }
    }

    private fun keyboardVisible() = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
}

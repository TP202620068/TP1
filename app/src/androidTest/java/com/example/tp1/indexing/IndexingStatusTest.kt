package com.example.tp1.indexing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.tp1.ui.components.IndexingStatus
import com.example.tp1.ui.theme.TP1Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class IndexingStatusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun incompleteWorkShowsCountsFailureReasonAndAnAccessibleRetry() {
        val retries = mutableListOf<String>()
        val state = IndexingState(mapOf("source" to SourceIndexingState(discovering = false, completed = 2,
            failures = listOf(IndexingFailure("scan.pdf", IndexingProblem.NeedsOcr)), incomplete = true)))
        compose.setContent { TP1Theme { IndexingStatus(state, retries::add) } }
        compose.onNodeWithText("No se pudo completar la búsqueda de documentos").assertIsDisplayed()
        compose.onNodeWithText("Indexados: 2 · Pendientes: 0 · Errores: 1").assertIsDisplayed()
        compose.onNodeWithText("scan.pdf: Requiere reconocimiento de texto").assertIsDisplayed()
        compose.onNodeWithText("Reintentar indexación").performClick()
        compose.runOnIdle { assertEquals(listOf("source"), retries) }
    }

    @Test fun missingModelIsAnExplicitBlockedStateRatherThanSuccess() {
        compose.setContent {
            TP1Theme { IndexingStatus(IndexingState(mapOf("source" to SourceIndexingState(pending = 1)), IndexingProblem.Model), {}) }
        }
        compose.onNodeWithText("Indexación detenida. Falta el modelo local Jina o no se pudo cargar.").assertIsDisplayed()
        compose.onNodeWithText("Indexación completada").assertDoesNotExist()
        compose.onNodeWithText("Reintentar indexación").assertIsDisplayed()
    }
}

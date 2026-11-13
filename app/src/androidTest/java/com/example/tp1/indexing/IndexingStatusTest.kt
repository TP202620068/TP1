package com.example.tp1.indexing

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.tp1.ui.components.IndexingStatus
import com.example.tp1.ui.theme.TP1Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class IndexingStatusTest {
    @get:Rule val compose = createComposeRule()
    private val progress = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)

    @Test fun runningWorkShowsProgressAndQuietOmissionsWithoutRetry() {
        var source by mutableStateOf(SourceIndexingState(discovering = false, pending = 6, running = true,
            failures = listOf(IndexingFailure("image.png", IndexingProblem.Unsupported),
                IndexingFailure("scan.pdf", IndexingProblem.NeedsOcr))))
        compose.setContent { TP1Theme { IndexingStatus(IndexingState(mapOf("source" to source)), {}) } }
        compose.onNodeWithText("Indexando documentos 0/6").assertIsDisplayed()
        compose.onNodeWithText("Omitidos: 2").assertIsDisplayed()
        compose.onNodeWithText("image.png", substring = true).assertDoesNotExist()
        compose.onNodeWithText("scan.pdf", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Reintentar indexación").assertDoesNotExist()
        compose.onNode(progress).assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        compose.runOnIdle { source = source.copy(completed = 2, pending = 4) }
        compose.onNodeWithText("Indexando documentos 2/6").assertIsDisplayed()
        compose.onNode(progress).assertRangeInfoEquals(ProgressBarRangeInfo(2f / 6, 0f..1f))
    }

    @Test fun omittedFilesAloneDoNotMakeCompletedWorkAFailure() {
        val source = SourceIndexingState(discovering = false, completed = 2,
            failures = listOf(IndexingFailure("scan.pdf", IndexingProblem.NeedsOcr)))
        compose.setContent { TP1Theme { IndexingStatus(IndexingState(mapOf("source" to source)), {}) } }
        compose.onNodeWithText("Documentos indexados: 2").assertIsDisplayed()
        compose.onNodeWithText("Omitidos: 1").assertIsDisplayed()
        compose.onNodeWithText("Indexación incompleta").assertDoesNotExist()
        compose.onNodeWithContentDescription("Reintentar indexación").assertDoesNotExist()
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test fun genuineFailureRemainsVisibleAndRetryWaitsUntilWorkStops() {
        val retries = mutableListOf<String>()
        var source by mutableStateOf(SourceIndexingState(discovering = false, completed = 2, pending = 1, running = true,
            failures = listOf(IndexingFailure("scan.pdf", IndexingProblem.NeedsOcr),
                IndexingFailure("broken.pdf", IndexingProblem.Document))))
        compose.setContent { TP1Theme { IndexingStatus(IndexingState(mapOf(
            "broken" to source,
            "omitted" to SourceIndexingState(discovering = false,
                failures = listOf(IndexingFailure("image.png", IndexingProblem.Unsupported)))
        )), retries::add) } }
        compose.onNodeWithText("Indexando documentos 2/4").assertIsDisplayed()
        compose.onNodeWithText("broken.pdf: No se pudo indexar el documento.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reintentar indexación").assertDoesNotExist()
        compose.runOnIdle { source = source.copy(pending = 0, running = false) }
        compose.onNodeWithText("Indexación incompleta").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reintentar indexación").performClick()
        compose.runOnIdle { assertEquals(listOf("broken"), retries) }
    }

    @Test fun incompleteDiscoveryIsStillAnActionableWarning() {
        val retries = mutableListOf<String>()
        val source = SourceIndexingState(discovering = false, completed = 2, incomplete = true,
            failures = listOf(IndexingFailure("scan.pdf", IndexingProblem.NeedsOcr)))
        compose.setContent { TP1Theme { IndexingStatus(IndexingState(mapOf("source" to source)), retries::add) } }
        compose.onNodeWithText("Indexación incompleta").assertIsDisplayed()
        compose.onNodeWithText("No se pudo completar la búsqueda de documentos").assertIsDisplayed()
        compose.onNodeWithText("Omitidos: 1").assertIsDisplayed()
        compose.onNodeWithText("scan.pdf", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Reintentar indexación").performClick()
        compose.runOnIdle { assertEquals(listOf("source"), retries) }
    }

    @Test fun missingModelIsAnExplicitBlockedStateRatherThanSuccess() {
        compose.setContent {
            TP1Theme { IndexingStatus(IndexingState(mapOf("source" to SourceIndexingState(pending = 1)), IndexingProblem.Model), {}) }
        }
        compose.onNodeWithText("Indexación detenida").assertIsDisplayed()
        compose.onNodeWithText("Falta el modelo local Jina o no se pudo cargar.").assertIsDisplayed()
        compose.onNodeWithText("Documentos indexados:", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Reintentar indexación").assertIsDisplayed()
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test fun storageBlockerKeepsItsExplanationAndRetry() {
        compose.setContent {
            TP1Theme { IndexingStatus(IndexingState(mapOf("source" to SourceIndexingState(pending = 1)), IndexingProblem.Storage), {}) }
        }
        compose.onNodeWithText("Indexación detenida").assertIsDisplayed()
        compose.onNodeWithText("No se pudo acceder al almacenamiento.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reintentar indexación").assertIsDisplayed()
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test fun chatWaitIsMarkedAsPausedWithoutAnAnimatedProgressBar() {
        val source = SourceIndexingState(discovering = false, completed = 1, pending = 2, waitingForChat = true)
        compose.setContent { TP1Theme { IndexingStatus(IndexingState(mapOf("source" to source)), {}) } }
        compose.onNodeWithText("Indexando documentos 1/3").assertIsDisplayed()
        compose.onNodeWithText("Indexación en espera del Chat").assertIsDisplayed()
        compose.onNodeWithContentDescription("Reintentar indexación").assertDoesNotExist()
        compose.onNode(progress).assertDoesNotExist()
    }

    @Test fun discoveryDoesNotInventATotalBeforeDocumentsAreFound() {
        var source by mutableStateOf(SourceIndexingState())
        compose.setContent { TP1Theme { IndexingStatus(IndexingState(mapOf("source" to source)), {}) } }
        compose.onNodeWithText("Indexando documentos…").assertIsDisplayed()
        compose.onNodeWithText("0/0", substring = true).assertDoesNotExist()
        compose.onNode(progress).assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.runOnIdle { source = source.copy(pending = 3) }
        compose.onNodeWithText("Indexando documentos 0/3").assertIsDisplayed()
        compose.onNode(progress).assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.runOnIdle { source = source.copy(discovering = false) }
        compose.onNode(progress).assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
    }

    @Test fun narrowLargeTextWrapsAndKeepsRetryAlignedAndAccessible() {
        val source = SourceIndexingState(discovering = false, completed = 2, incomplete = true,
            failures = listOf(IndexingFailure("scan.pdf", IndexingProblem.NeedsOcr)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                TP1Theme { IndexingStatus(IndexingState(mapOf("source" to source)), {},
                    Modifier.width(320.dp).testTag("indexing-card")) }
            }
        }
        val title = compose.onNodeWithText("Indexación incompleta")
        val omitted = compose.onNodeWithText("Omitidos: 1")
        val retry = compose.onNodeWithContentDescription("Reintentar indexación")
        title.assertIsDisplayed()
        omitted.assertIsDisplayed()
        retry.assertIsDisplayed()
        val cardBounds = compose.onNodeWithTag("indexing-card").getUnclippedBoundsInRoot()
        val retryBounds = retry.getUnclippedBoundsInRoot()
        assertEquals(cardBounds.right - 16.dp, retryBounds.right)
        assertTrue(retryBounds.bottom - retryBounds.top >= 48.dp)
        assertTrue(retryBounds.right - retryBounds.left >= 48.dp)
        assertTrue(omitted.getUnclippedBoundsInRoot().top >= title.getUnclippedBoundsInRoot().bottom)
        for (text in listOf(title, omitted)) {
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            assertFalse(layouts.single().hasVisualOverflow)
        }
    }
}

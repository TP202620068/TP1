package com.example.tp1.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.tp1.inference.FinishReason
import com.example.tp1.browser.DocumentReference
import com.example.tp1.ui.AppTab
import com.example.tp1.ui.TP1App
import com.example.tp1.ui.theme.TP1Theme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatDocumentsTest {
    @get:Rule val compose = createComposeRule()
    private fun documents() = PreparedChatRequest(emptyList(), listOf(
        ChatGroundingTest.match(), ChatGroundingTest.match().copy(ordinal = 1),
        ChatGroundingTest.match(2, "b").copy(displayName = "a.txt")
    )).documents

    @Test fun deduplicatesFilesNotNamesAndExpansionSurvivesTabsAndStateRestoration() {
        val docs = documents()
        assertEquals(2, docs.size)
        var opened: DocumentReference? = null
        val restore = StateRestorationTester(compose)
        restore.setContent {
            TP1Theme { TP1App(initialTab = AppTab.Chat, chat = ChatState(turns = listOf(
                ChatTurn(1, "question", "raw answer [not a citation]", finish = FinishReason.EOG, documents = docs))),
                onChatOpenDocument = { opened = it }) }
        }
        compose.onNodeWithText("a.txt").assertDoesNotExist()
        val button = compose.onNodeWithContentDescription("Fuentes: 2")
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Lista contraída"))
        button.performClick()
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Lista expandida"))
        compose.onAllNodesWithText("a.txt").assertCountEquals(2)
        compose.onNodeWithText("raw answer [not a citation]").assertIsDisplayed()
        compose.onAllNodesWithText("a.txt")[1].performClick()
        compose.runOnIdle { assertEquals(docs[1], opened) }
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        compose.onAllNodesWithText("a.txt").assertCountEquals(2)
        restore.emulateSavedInstanceStateRestore()
        compose.onAllNodesWithText("a.txt").assertCountEquals(2)
        button.performClick()
        compose.onNodeWithText("a.txt").assertDoesNotExist()
    }

    @Test fun sourcesBubbleIsSeparateFromAnswerAndExpansionDoesNotResizeAnswer() {
        compose.setContent { TP1Theme { TP1App(initialTab = AppTab.Chat, chat = ChatState(turns = listOf(
            ChatTurn(1, "question", "answer", finish = FinishReason.EOG, documents = documents())))) } }
        val assistant = compose.onNodeWithContentDescription("Asistente")
        val button = compose.onNodeWithContentDescription("Fuentes: 2")
        val answerBounds = assistant.fetchSemanticsNode().boundsInRoot
        button.assertHeightIsAtLeast(48.dp)
        button.assert(hasAnyAncestor(hasContentDescription("Asistente")).not())
        compose.onNodeWithText("Fuentes").assertIsDisplayed()
        assertTrue(button.fetchSemanticsNode().boundsInRoot.top > answerBounds.bottom)

        button.performClick()
        compose.onAllNodesWithText("a.txt").assertCountEquals(2)
        compose.onAllNodesWithText("a.txt")[0].assert(hasAnyAncestor(hasContentDescription("Asistente")).not())
        compose.onNodeWithText("Documentos enviados al modelo como contexto.").assertDoesNotExist()
        assertEquals(answerBounds, assistant.fetchSemanticsNode().boundsInRoot)
        assertTrue(button.fetchSemanticsNode().boundsInRoot.top > answerBounds.bottom)

        button.performClick()
        compose.onNodeWithText("a.txt").assertDoesNotExist()
        assertEquals(answerBounds, assistant.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun sourcesAppearOnlyAfterVisibleAnswerStopsAndErrorsAreVisibleInsideChat() {
        var state by mutableStateOf(ChatState(turns = listOf(ChatTurn(1, "question", "partial", running = true,
            documents = documents()))))
        compose.setContent { TP1Theme { TP1App(initialTab = AppTab.Chat, chat = state) } }
        compose.onNodeWithContentDescription("Fuentes: 2").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(turns = listOf(state.turns.single().copy(running = false,
            finish = FinishReason.CANCELLED))) }
        compose.onNodeWithContentDescription("Fuentes: 2").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(turns = listOf(state.turns.single().copy(documents = emptyList()))) }
        compose.onNodeWithText("Fuentes").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(turns = listOf(state.turns.single().copy(documents = documents()))) }
        compose.onNodeWithContentDescription("Fuentes: 2").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(turns = listOf(state.turns.single().copy(answer = ""))) }
        compose.onNodeWithContentDescription("Fuentes: 2").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(openProblem = ChatOpenProblem.NoViewer) }
        compose.onNodeWithText("No hay una aplicación compatible para abrir este archivo.").assertIsDisplayed()
    }
}

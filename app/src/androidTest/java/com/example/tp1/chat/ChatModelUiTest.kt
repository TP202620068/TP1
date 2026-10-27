package com.example.tp1.chat

import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.example.tp1.MainActivity
import com.example.tp1.PickerRobot
import com.example.tp1.awaitSources
import com.example.tp1.extraction.ExtractionArtifacts
import com.example.tp1.inference.FinishReason
import com.example.tp1.inference.ManualInference
import com.example.tp1.inference.Model
import com.example.tp1.inference.ModelManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Explicit real-model UI proof. Ordinary instrumentation never downloads or loads weights. */
@ManualInference
class ChatModelUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val picker = PickerRobot()

    @After fun cleanUp() = picker.cleanUp()

    @Test fun realStreamAndTranscriptSurviveRecreationWithoutSelectedFiles() {
        runBlocking {
            check(ModelManager(compose.activity).isAvailable(Model.Test)) { "Provision the pinned model first" }
        }
        picker.resetSources()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        compose.onNodeWithText("Preparar chat").performClick()
        val owner = ViewModelProvider(compose.activity)[ChatViewModel::class.java]
        compose.waitUntil(60_000) { owner.state.value.preparation == ChatPreparation.Ready }
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput("<|startoftext|> Hola")
        compose.onNodeWithContentDescription("Enviar pregunta").performClick()
        compose.waitUntil(10_000) { owner.state.value.phase == ChatPhase.Idle && owner.state.value.problem != null }
        compose.runOnIdle {
            assertEquals("<|startoftext|> Hola", owner.state.value.draft)
            assertTrue(owner.state.value.turns.isEmpty())
        }
        compose.onNodeWithContentDescription("Tu pregunta").performTextReplacement(
            "Count from 1 to 100, writing every number separated by commas. Do not explain.")
        val started = SystemClock.elapsedRealtime()
        compose.onNodeWithContentDescription("Enviar pregunta").performClick()
        compose.waitUntil(60_000) {
            owner.state.value.turns.singleOrNull()?.let { it.running && it.answer.isNotBlank() } == true
        }
        compose.runOnIdle { owner.editDraft("Borrador sin enviar") }
        compose.activityRule.scenario.recreate()
        compose.onNode(hasText("Chat") and hasClickAction()).assertIsSelected()
        assertSame(owner, ViewModelProvider(compose.activity)[ChatViewModel::class.java])
        compose.waitUntil(150_000) { owner.state.value.phase == ChatPhase.Idle }
        compose.runOnIdle {
            val turn = owner.state.value.turns.single()
            assertTrue(turn.answer.isNotBlank())
            assertTrue("Unexpected finish: ${turn.finish}", turn.finish in setOf(
                FinishReason.EOG, FinishReason.LENGTH, FinishReason.DEADLINE))
            if (turn.finish == FinishReason.DEADLINE) {
                assertTrue("Deadline must not finish early", SystemClock.elapsedRealtime() - started >= 120_000)
            }
            assertEquals("Borrador sin enviar", owner.state.value.draft)
        }
        compose.onNodeWithText(owner.state.value.turns.single().answer).assertIsDisplayed()
        if (owner.state.value.turns.single().finish == FinishReason.DEADLINE) {
            compose.onNodeWithText("Se agotó el tiempo. La respuesta está incompleta.").assertIsDisplayed()
        }
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        compose.waitUntil(60_000) { owner.state.value.preparation == ChatPreparation.Unprepared }
        val released = ExtractionArtifacts.permit.tryLock()
        if (released) ExtractionArtifacts.permit.unlock()
        assertTrue("Leaving Chat must release the model's native memory permit", released)
    }
}

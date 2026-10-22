package com.example.tp1

import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class ShellNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val picker = PickerRobot()

    @Before
    fun startWithoutSources() {
        picker.resetSources()
        compose.activityRule.scenario.recreate()
        compose.awaitSources()
    }

    @After fun cleanUp() = picker.cleanUp()

    @Test
    fun tabsAndTypedTextSurviveActivityRecreation() {
        compose.onNode(hasText("Explorador") and hasClickAction()).assertIsSelected()
        compose.onNodeWithContentDescription("Buscar archivos").performTextInput("documentos")
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.runOnIdle { compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }

        compose.onNodeWithText("Sin archivos").assertIsDisplayed()
        compose.onNodeWithText("Fuentes").assertDoesNotExist()
        compose.onNodeWithText("Añadir").assertIsEnabled()

        compose.onNode(hasText("Chat") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Enviar pregunta").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Tu pregunta").performTextInput("¿Qué dice mi contrato?")
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        val tabHeight = compose.onNode(hasText("Chat") and hasClickAction())
            .getUnclippedBoundsInRoot().let { it.bottom - it.top }
        assertTrue("Keyboard insets must not inflate the tab bar", tabHeight in 48.dp..120.dp)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, compose.activity.requestedOrientation)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
            compose.waitForIdle()
            assertEquals(Configuration.ORIENTATION_PORTRAIT, compose.activity.resources.configuration.orientation)
            compose.onNodeWithContentDescription("Tu pregunta").assertIsDisplayed()
            compose.onNodeWithContentDescription("Tu pregunta").assertTextEquals("¿Qué dice mi contrato?")
        } finally {
            automation.setRotation(UiAutomation.ROTATION_UNFREEZE)
        }

        compose.activityRule.scenario.recreate()

        compose.onNode(hasText("Chat") and hasClickAction()).assertIsSelected()
        compose.onNodeWithContentDescription("Tu pregunta").assertTextEquals("¿Qué dice mi contrato?")
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Buscar archivos").assertTextEquals("documentos")
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Tu pregunta").assertTextEquals("¿Qué dice mi contrato?")
        compose.runOnIdle { compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        compose.waitUntil(5_000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == false
        }
        compose.onNode(hasText("Explorador") and hasClickAction()).performClick()
        compose.onNodeWithText("Añadir").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNode(hasText("Explorador") and hasClickAction()).assertIsSelected()
        compose.onNodeWithText("Añadir").performClick()
        compose.onNodeWithText("Listo").performScrollTo().performClick()
        compose.onNode(hasText("Explorador") and hasClickAction()).assertIsSelected()
        compose.onNodeWithContentDescription("Buscar archivos").assertTextEquals("documentos")
        compose.onNode(hasText("Chat") and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("Tu pregunta").assertTextEquals("¿Qué dice mi contrato?")
    }
}

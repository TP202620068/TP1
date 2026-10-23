package com.example.tp1

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.tp1.browser.BrowserFolder
import com.example.tp1.browser.BrowserProblem
import com.example.tp1.browser.BrowserState
import com.example.tp1.ui.TP1App
import com.example.tp1.ui.components.FileKind
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.theme.TP1Theme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ExplorerFeedbackTest {
    @get:Rule val compose = createComposeRule()

    @Test fun openingRefreshAndSnackbarKeepFileRowsInPlace() {
        var browser by mutableStateOf(BrowserState(sourceUri = "preview", path = listOf(BrowserFolder("preview", "Documentos"))))
        var retries = 0
        compose.setContent {
            TP1Theme {
                TP1App(
                    files = listOf(FileListItem("Contrato.pdf", FileKind.Pdf, id = "preview-file")),
                    browser = browser,
                    onEntryClick = { browser = browser.copy(opening = true) },
                    onMessageDismissed = { if (browser.message == it) browser = browser.copy(message = null) },
                    onRetry = { retries++ }
                )
            }
        }
        val file = compose.onNodeWithText("Contrato.pdf")
        val before = file.getUnclippedBoundsInRoot()
        file.performClick()
        compose.onNodeWithContentDescription("Abriendo archivo…").assertIsDisplayed()
        assertEquals(before, file.getUnclippedBoundsInRoot())

        compose.runOnIdle { browser = browser.copy(opening = false, message = BrowserProblem.NoViewer) }
        compose.onNodeWithText("No hay una aplicación compatible para abrir este archivo.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Abriendo archivo…").assertDoesNotExist()
        assertEquals(before, file.getUnclippedBoundsInRoot())

        compose.runOnIdle { browser = browser.copy(message = null, loading = true) }
        compose.onNodeWithContentDescription("Cargando carpeta…").assertIsDisplayed()
        assertEquals(before, file.getUnclippedBoundsInRoot())

        compose.runOnIdle { browser = browser.copy(loading = false, message = BrowserProblem.Missing) }
        compose.onNodeWithText("Reintentar").performClick()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(null, browser.message) }
        assertEquals(before, file.getUnclippedBoundsInRoot())
    }
}

package com.example.tp1.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.tp1.ui.components.FileKind
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.components.FileRow
import com.example.tp1.ui.theme.TP1Theme
import com.example.tp1.sources.SelectedSource
import com.example.tp1.sources.SourceKind
import com.example.tp1.sources.SourceMetadata
import com.example.tp1.sources.SourceRecord
import com.example.tp1.ui.screens.SourceSelectionScreen

private object ShellFixtures {
    val files = listOf(
        FileListItem("Contrato de alquiler.pdf", FileKind.Pdf, "2,4 MB · Hace 3 días"),
        FileListItem("Recibo de compra.jpg", FileKind.Image, "1,1 MB · 14 may 2024"),
        FileListItem("Declaración de la renta 2023.pdf", FileKind.Pdf, "890 KB · 10 abr 2024"),
        FileListItem("Póliza seguro médico.pdf", FileKind.Pdf, "3,1 MB · 22 ene 2024"),
        FileListItem("Notas reunión hipoteca.docx", FileKind.Document, "45 KB · 28 may 2024"),
        FileListItem("Factura electricidad junio.pdf", FileKind.Pdf, "320 KB · 02 jun 2024"),
        FileListItem("Escaneo de documento.png", FileKind.Image, "4,2 MB · 15 feb 2024"),
        FileListItem("Garantía electrodomésticos.pdf", FileKind.Pdf, "1,8 MB · 11 nov 2023")
    )
}

@Preview(name = "Explorador", device = "spec:width=390dp,height=844dp,dpi=440", showSystemUi = true)
@Composable
private fun ExplorerPreview() {
    TP1Theme { TP1App(files = ShellFixtures.files) }
}

@Preview(name = "Chat", device = "spec:width=390dp,height=844dp,dpi=440", showSystemUi = true)
@Composable
private fun ChatPreview() {
    TP1Theme { TP1App(initialTab = AppTab.Chat) }
}

@Preview(name = "Sin fuentes", device = "spec:width=390dp,height=844dp,dpi=440", showSystemUi = true)
@Composable
private fun EmptyExplorerPreview() {
    TP1Theme { TP1App() }
}

@Preview(name = "Nombre largo y texto grande", widthDp = 390, heightDp = 300, fontScale = 2f)
@Composable
private fun LongFilenamePreview() {
    TP1Theme {
        Surface {
            FileRow(
                FileListItem(
                    "Contrato_de_alquiler_de_vivienda_firmado_y_revisado_octubre_2026.pdf",
                    FileKind.Pdf,
                    "Documentos / Legal · 2,4 MB"
                )
            )
        }
    }
}

@Preview(name = "Selección vacía", widthDp = 390, heightDp = 844, showSystemUi = true)
@Composable
private fun EmptySelectionPreview() = SelectionPreview(emptyList())

@Preview(name = "Selección con texto grande", widthDp = 320, heightDp = 740, fontScale = 2f)
@Composable
private fun PopulatedSelectionPreview() = SelectionPreview(listOf(
    SelectedSource(
        SourceRecord("preview", SourceKind.Document),
        SourceMetadata("Contrato_de_alquiler_de_vivienda_firmado_octubre_2026.pdf", "application/pdf", 12000, null)
    )
))

@Composable
private fun SelectionPreview(sources: List<SelectedSource>) {
    TP1Theme {
        SourceSelectionScreen(
            sources = sources, busy = false, storageReady = true, canClose = true,
            message = null, onPick = {}, onRemove = {}, onClose = {}, onRetry = {}
        )
    }
}

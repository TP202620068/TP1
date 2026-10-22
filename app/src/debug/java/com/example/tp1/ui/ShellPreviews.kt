package com.example.tp1.ui

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.tp1.ui.components.FileKind
import com.example.tp1.ui.components.FileListItem
import com.example.tp1.ui.components.FileRow
import com.example.tp1.ui.theme.TP1Theme

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
    val sources = listOf(
        FileListItem("Documentos personales", FileKind.Folder, "Documentos"),
        FileListItem("Facturas", FileKind.Folder, "Descargas / Facturas"),
        FileListItem("Hogar y Finanzas", FileKind.Folder, "Documentos / Finanzas"),
        FileListItem("Contrato_alquiler_firmado.pdf", FileKind.Pdf, "Documentos / Legal · 2,4 MB"),
        FileListItem("Manual_garantia_laptop.pdf", FileKind.Pdf, "Descargas · 4,1 MB")
    )
}

@Preview(name = "Explorador", device = "spec:width=390dp,height=844dp,dpi=440", showSystemUi = true)
@Composable
private fun ExplorerPreview() {
    TP1Theme { TP1App(files = ShellFixtures.files) }
}

@Preview(name = "Fuentes", device = "spec:width=390dp,height=844dp,dpi=440", showSystemUi = true)
@Composable
private fun SourcesPreview() {
    TP1Theme { TP1App(initialTab = AppTab.Sources, sources = ShellFixtures.sources) }
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

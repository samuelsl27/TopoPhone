package es.topophone.core.survey

import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportRowError
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El informe de errores de una importación como texto (S028/RF-24). */
class ImportReportTextTest {

    private val reasonText = { reason: ImportErrorReason -> "motivo ${reason.name}" }
    private val lineFormat = { line: Int, reason: String -> "Línea $line: $reason" }

    private fun build(errors: List<ImportRowError>) = ImportReportText.build(
        title = "Errores de puntos.csv",
        summary = "4 filas, ${errors.size} con error",
        errors = errors,
        reasonText = reasonText,
        lineFormat = lineFormat,
        emptyText = "Ninguna fila con error",
    )

    @Test
    fun `S028 RF-24 el informe lleva el titulo, el resumen y una linea por fila erronea con su linea y su motivo`() {
        val text = build(
            listOf(
                ImportRowError(3, ImportErrorReason.MISSING_NAME),
                ImportRowError(10, ImportErrorReason.NOT_A_NUMBER),
            ),
        )

        text shouldBe
            "Errores de puntos.csv\n" +
            "4 filas, 2 con error\n" +
            "\n" +
            "Línea 3: motivo MISSING_NAME\n" +
            "Línea 10: motivo NOT_A_NUMBER\n"
    }

    @Test
    fun `S028 RF-24 las filas erroneas salen en el orden en que se dan`() {
        val text = build(listOf(ImportRowError(9, ImportErrorReason.OUTSIDE_GEOID), ImportRowError(2, ImportErrorReason.MISSING_COLUMNS)))

        text.lines().filter { it.startsWith("Línea") } shouldBe listOf("Línea 9: motivo OUTSIDE_GEOID", "Línea 2: motivo MISSING_COLUMNS")
    }

    @Test
    fun `S028 RF-24 sin errores el informe lo dice`() {
        build(emptyList()) shouldBe "Errores de puntos.csv\n4 filas, 0 con error\n\nNinguna fila con error\n"
    }

    @Test
    fun `S028 RF-24 cada motivo de error pasa por el texto que da quien llama`() {
        val all = ImportErrorReason.entries.mapIndexed { i, reason -> ImportRowError(i + 1, reason) }

        val text = build(all)

        ImportErrorReason.entries.forEach { text.contains("motivo ${it.name}") shouldBe true }
    }

    @Test
    fun `S028 RF-24 el numero de linea no usa la configuracion regional`() {
        val text = ImportReportText.build(
            title = "t",
            summary = "s",
            errors = listOf(ImportRowError(1234567, ImportErrorReason.MISSING_NAME)),
            reasonText = reasonText,
            lineFormat = { line, reason -> "$line|$reason" },
            emptyText = "e",
        )

        text.contains("1234567|motivo MISSING_NAME") shouldBe true
    }

    @Test
    fun `S028 RF-24 un informe de muchos errores se construye sin problema`() {
        val errors = (1..50_000).map { ImportRowError(it, ImportErrorReason.NOT_A_NUMBER) }

        val text = build(errors)

        text.lines().size shouldBe 50_000 + 4 // título, resumen, línea en blanco, los errores y el salto final
    }
}

package es.topophone.core.model.line

import es.topophone.core.model.codes.CodeGeometry
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Elegir, ordenar e invertir los puntos del asistente «Línea desde puntos» (S025/RF-12, RF-13, RF-15). */
class LineWizardTest {

    @Test
    fun `S025 RF-12 tocar un punto lo anade al final y tocarlo otra vez lo quita`() {
        var ids = emptyList<String>()

        ids = LineWizard.toggle(ids, "b")
        ids = LineWizard.toggle(ids, "a")
        ids = LineWizard.toggle(ids, "c")
        ids shouldBe listOf("b", "a", "c")

        LineWizard.toggle(ids, "a") shouldBe listOf("b", "c")
    }

    @Test
    fun `S025 RF-12 ordenar por nombre respeta los numeros`() {
        val names = mapOf("p1" to "10", "p2" to "2", "p3" to "1", "p4" to "A5")

        LineWizard.sortedByName(listOf("p1", "p2", "p3", "p4"), names) shouldBe listOf("p3", "p2", "p1", "p4")
    }

    @Test
    fun `S025 RF-13 invertir cambia el orden y no toca los puntos`() {
        LineWizard.reversed(listOf("a", "b", "c")) shouldBe listOf("c", "b", "a")
    }

    @Test
    fun `S025 RF-13 un codigo de poligono propone la linea cerrada y uno de linea abierta`() {
        LineWizard.defaultClosed(CodeGeometry.POLYGON) shouldBe true
        LineWizard.defaultClosed(CodeGeometry.LINE) shouldBe false
        LineWizard.defaultClosed(null) shouldBe false
    }

    @Test
    fun `S025 RF-15 con menos de 2 puntos, o 3 en un poligono, no se puede crear`() {
        LineWizard.issue(listOf("a"), closed = false) shouldBe LineIssue.TOO_FEW_VERTICES
        LineWizard.issue(listOf("a", "b"), closed = true) shouldBe LineIssue.TOO_FEW_VERTICES
        LineWizard.issue(listOf("a", "b"), closed = false).shouldBeNull()
        LineWizard.issue(listOf("a", "b", "c"), closed = true).shouldBeNull()
    }
}

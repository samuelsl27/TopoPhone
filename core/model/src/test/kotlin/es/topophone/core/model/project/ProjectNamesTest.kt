package es.topophone.core.model.project

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Nombres de proyectos y carpetas, búsqueda y aviso de datum (S006/RF-4, RF-7, RF-11, RF-16, RF-25). */
class ProjectNamesTest {

    @Test
    fun `S006 RF-7 un nombre vacio o con solo espacios no vale`() {
        ProjectNames.validate("", emptyList()) shouldBe NameError.EMPTY
        ProjectNames.validate("   ", emptyList()) shouldBe NameError.EMPTY
    }

    @Test
    fun `S006 RF-7 un nombre que coincide con otro sin distinguir mayusculas no vale`() {
        val existing = listOf("Calle Mayor 12", "Parcela 23")

        ProjectNames.validate("calle mayor 12", existing) shouldBe NameError.DUPLICATE
        ProjectNames.validate("  PARCELA 23 ", existing) shouldBe NameError.DUPLICATE
        ProjectNames.validate("Calle Mayor 14", existing).shouldBeNull()
    }

    @Test
    fun `S006 RF-7 tambien se comparan sin tildes`() {
        ProjectNames.validate("Camino de Avila", listOf("Camino de Ávila")) shouldBe NameError.DUPLICATE
    }

    @Test
    fun `S006 RF-4 la busqueda encuentra el texto en cualquier parte sin distinguir mayusculas ni tildes`() {
        ProjectNames.matches("Replanteo Ávila Norte", "avila") shouldBe true
        ProjectNames.matches("Replanteo Ávila Norte", "NORTE") shouldBe true
        ProjectNames.matches("Replanteo Ávila Norte", "sur") shouldBe false
        ProjectNames.matches("Cualquiera", "") shouldBe true
    }

    @Test
    fun `S006 RF-16 la copia se llama nombre (copia) y si ya existe se numera`() {
        ProjectNames.copyName("Parcela 23", listOf("Parcela 23"), "copia") shouldBe "Parcela 23 (copia)"
        ProjectNames.copyName("Parcela 23", listOf("Parcela 23", "Parcela 23 (copia)"), "copia") shouldBe "Parcela 23 (copia 2)"
        ProjectNames.copyName("Parcela 23", listOf("Parcela 23", "parcela 23 (COPIA)", "Parcela 23 (copia 2)"), "copia") shouldBe
            "Parcela 23 (copia 3)"
    }

    @Test
    fun `S006 RF-25 al restaurar se conserva el nombre si esta libre y si no se marca como restaurado`() {
        ProjectNames.restoredName("Parcela 23", listOf("Otro"), "restaurado") shouldBe "Parcela 23"
        ProjectNames.restoredName("Parcela 23", listOf("parcela 23"), "restaurado") shouldBe "Parcela 23 (restaurado)"
        ProjectNames.restoredName("Parcela 23", listOf("Parcela 23", "Parcela 23 (restaurado)"), "restaurado") shouldBe
            "Parcela 23 (restaurado 2)"
    }

    @Test
    fun `S006 RF-11 se avisa si las correcciones estan en ITRF-WGS84 y el SC en ETRS89`() {
        ProjectCrs.entries.forEach { crs ->
            DatumCheck.mismatch(crs, CorrectionsDatum.ITRF_WGS84) shouldBe true
            DatumCheck.mismatch(crs, CorrectionsDatum.ETRS89) shouldBe false
            // REGCAN95 es la realización de ETRS89 en Canarias: compatible al nivel del centímetro.
            DatumCheck.mismatch(crs, CorrectionsDatum.REGCAN95) shouldBe false
        }
    }
}

package es.topophone.core.model.cad

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Lo que gana el modelo de los dibujos con la importación de KML y KMZ (S031/RF-9, RF-16). */
class KmlTypesTest {

    @Test
    fun `S031 RF-16 el origen de un dibujo puede ser un KML`() {
        CadOrigin.valueOf("KML") shouldBe CadOrigin.KML
    }

    @Test
    fun `S031 RF-9 los elementos de un KML que no se importan tienen su motivo`() {
        GpkgIgnoredReason.entries.map { it.name } shouldContainAll
            listOf("NETWORK_LINK", "OVERLAY", "MODEL", "TRACK", "TOUR")
    }

    @Test
    fun `S031 RF-9 el resumen suma los elementos ignorados del fichero`() {
        val summary = GpkgImportSummary(
            layers = emptyList(),
            ignoredElements = mapOf(GpkgIgnoredReason.NETWORK_LINK to 2, GpkgIgnoredReason.MODEL to 1, GpkgIgnoredReason.TRACK to 4),
        )

        summary.ignoredElementsTotal shouldBe 7
    }

    @Test
    fun `S031 RF-9 un resumen sin elementos ignorados vale cero`() {
        GpkgImportSummary(layers = emptyList()).ignoredElementsTotal shouldBe 0
    }
}

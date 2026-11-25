package es.topophone.core.model.surface

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El nombre de una superficie que el fichero no nombra (S061/RF-1, RF-2). */
class SurfaceNamesTest {

    @Test
    fun `S061 RF-1 con nombre se deja tal cual`() {
        SurfaceNames.display("terreno", "obra.xml") shouldBe "terreno"
        SurfaceNames.display(" rasante 2 ", "obra.xml") shouldBe " rasante 2 "
    }

    @Test
    fun `S061 RF-1 sin nombre o en blanco toma el del fichero sin la extension`() {
        SurfaceNames.display("", "explanacion.xml") shouldBe "explanacion"
        SurfaceNames.display("   ", "explanacion.xml") shouldBe "explanacion"
        SurfaceNames.display(null, "explanacion.xml") shouldBe "explanacion"
    }

    @Test
    fun `S061 RF-1 solo se quita la ultima extension`() {
        SurfaceNames.display("", "fase.1.landxml.xml") shouldBe "fase.1.landxml"
        SurfaceNames.display("", "sin_extension") shouldBe "sin_extension"
        SurfaceNames.display("", ".xml") shouldBe ".xml"
    }
}

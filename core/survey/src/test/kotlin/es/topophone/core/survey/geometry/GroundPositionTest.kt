package es.topophone.core.survey.geometry

import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryFixtures.number
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** De Este y Norte del SC del proyecto a latitud y longitud, para el factor de terreno de un dibujo (S026/RF-10). */
class GroundPositionTest {

    @Test
    fun `S026 RF-10 Este y Norte UTM 30 vuelven a la latitud y longitud de pyproj`() {
        for (row in GeometryFixtures.rows("vertices.csv").filter { it.getValue("este").isNotEmpty() }) {
            val ground = GroundPosition.of(ProjectCrs.ETRS89_UTM30, row.number("este"), row.number("norte")).shouldNotBeNull()
            ground.first shouldBe (row.number("latitud") plusOrMinus 2e-9)
            ground.second shouldBe (row.number("longitud") plusOrMinus 2e-9)
        }
    }

    @Test
    fun `S026 RF-10 en un SC geografico las coordenadas ya son longitud y latitud`() {
        GroundPosition.of(ProjectCrs.ETRS89_GEOGRAPHIC, -3.7038, 40.4168) shouldBe (40.4168 to -3.7038)
    }

    @Test
    fun `S026 RF-10 en ED50 sale de la rejilla hacia ETRS89 y fuera de ella no hay posicion`() {
        val inside = GroundPosition.of(ProjectCrs.ED50_UTM30, 440_000.0, 4_474_000.0).shouldNotBeNull()
        // ED50 y ETRS89 difieren en unos 100 m: la latitud sale distinta de la de ETRS89 con las mismas coordenadas.
        val etrs = GroundPosition.of(ProjectCrs.ETRS89_UTM30, 440_000.0, 4_474_000.0).shouldNotBeNull()
        (inside.first != etrs.first) shouldBe true
        GroundPosition.of(ProjectCrs.ED50_UTM30, 0.0, 0.0) shouldBe null
    }
}

package es.topophone.core.survey

import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Dónde cae un dibujo respecto al proyecto: las geográficas de un punto del proyecto y la distancia entre dos (S040/RF-9, RF-10). */
class CadPlacementTest {

    @Test
    fun `S040 RF-9 un punto UTM del proyecto vuelve a sus geograficas de pyproj`() {
        // Madrid: 40,41668 N, 3,70371 O es el (440297,9875; 4474244,0011) en ETRS89 UTM 30 (pyproj, EPSG:25830).
        val (lat, lon) = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, 440_297.9875, 4_474_244.0011)!!

        lat shouldBe (40.41668 plusOrMinus 1e-8)
        lon shouldBe (-3.70371 plusOrMinus 1e-8)
    }

    @Test
    fun `S040 RF-9 en un proyecto en geograficas el Este es la longitud y el Norte la latitud`() {
        CadPlacement.geographic(ProjectCrs.ETRS89_GEOGRAPHIC, -3.70371, 40.41668) shouldBe (40.41668 to -3.70371)
    }

    @Test
    fun `S040 RF-9 en ED50 el resultado esta en ETRS89 y se desplaza unos cientos de metros`() {
        val ed50 = CadPlacement.geographic(ProjectCrs.ED50_UTM30, 440_297.9875, 4_474_244.0011)!!
        val etrs = CadPlacement.geographic(ProjectCrs.ETRS89_UTM30, 440_297.9875, 4_474_244.0011)!!

        val metres = CadPlacement.distanceKm(ed50.first, ed50.second, etrs.first, etrs.second) * 1000
        (metres in 50.0..300.0) shouldBe true
    }

    @Test
    fun `S040 RF-10 la distancia entre Madrid y Valencia es la de la gran circunferencia`() {
        // Madrid (40,41668; -3,70371) a Valencia (39,4699; -0,3763): unos 302 km por la fórmula de Vincenty/haversine.
        CadPlacement.distanceKm(40.41668, -3.70371, 39.4699, -0.3763) shouldBe (302.0 plusOrMinus 3.0)
        CadPlacement.distanceKm(10.0, 10.0, 10.0, 10.0) shouldBe 0.0
    }

    @Test
    fun `S040 RF-10 la distancia de un grado de meridiano son unos 111 km`() {
        CadPlacement.distanceKm(40.0, 0.0, 41.0, 0.0) shouldBe (111.2 plusOrMinus 0.3)
    }

    @Test
    fun `S040 RF-10 el aviso salta a mas de 100 km y no a 100`() {
        CadPlacement.FAR_KM shouldBe 100.0
        CadPlacement.isFar(100.0) shouldBe false
        CadPlacement.isFar(100.01) shouldBe true
        CadPlacement.isFar(null) shouldBe false
    }
}

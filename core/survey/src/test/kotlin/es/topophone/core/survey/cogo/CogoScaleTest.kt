package es.topophone.core.survey.cogo

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.cogo.CogoFixtures.number
import es.topophone.core.survey.geometry.GeometryNotice
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El factor de terreno de COGO: el combinado del primer punto de entrada (S038/RF-5), frente al de pyproj. */
class CogoScaleTest {

    @Test
    fun `S038 RF-5 el factor del primer punto coincide con pyproj en los tres sistemas a menos de 1e-9`() {
        val rows = CogoFixtures.rows("inversas.csv").filter { it.getValue("modo") == "grid" }
        rows.size shouldBe 3 * 14
        for (row in rows) {
            val crs = CogoFixtures.crs(row)
            val first = CogoFixtures.points(crs).getValue(row.getValue("punto_a"))
            val scale = CogoScale.forFirst(first, crs, DistanceMode.GRID)

            scale.factor shouldBe (row.number("factor") plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S038 RF-5 un punto escrito a mano toma su posicion de su Este y su Norte`() {
        val crs = ProjectCrs.ETRS89_UTM30
        val known = CogoFixtures.points(crs).getValue("P3")
        val typed = CogoPoint(known.easting, known.northing, ellipsoidalHeightM = known.ellipsoidalHeightM)

        val scale = CogoScale.forFirst(typed, crs, DistanceMode.GROUND)

        scale.factor shouldBe (CogoScale.forFirst(known, crs, DistanceMode.GROUND).factor plusOrMinus 1e-9)
        scale.notices shouldBe emptySet()
    }

    @Test
    fun `S038 RF-5 en terreno lo escrito se pasa a cuadricula con el factor y en cuadricula no cambia`() {
        val crs = ProjectCrs.ETRS89_UTM30
        val first = CogoFixtures.points(crs).getValue("P1")
        val ground = CogoScale.forFirst(first, crs, DistanceMode.GROUND)
        val grid = CogoScale.forFirst(first, crs, DistanceMode.GRID)

        grid.toGrid(100.0) shouldBe 100.0
        grid.toActive(100.0) shouldBe 100.0
        ground.toGrid(100.0) shouldBe (100.0 * ground.factor plusOrMinus 1e-12)
        ground.toActive(ground.toGrid(123.456)) shouldBe (123.456 plusOrMinus 1e-12)
        (ground.factor < 1.0) shouldBe true // a 650 m de altura
    }

    @Test
    fun `S038 RF-5 sin altura elipsoidal solo se usa el factor de escala y se avisa`() {
        val crs = ProjectCrs.ETRS89_UTM30
        val first = CogoFixtures.points(crs).getValue("P7") // sin altura

        val scale = CogoScale.forFirst(first, crs, DistanceMode.GROUND)

        scale.notices shouldBe setOf(GeometryNotice.FACTOR_WITHOUT_HEIGHT)
        scale.factor shouldBe (0.99961 plusOrMinus 3e-4) // k del UTM30 cerca del meridiano central
    }

    @Test
    fun `S038 RF-5 un sistema geografico no da factor y se avisa`() {
        val first = CogoPoint(easting = -3.7, northing = 40.4, latitudeDeg = 40.4, longitudeDeg = -3.7, ellipsoidalHeightM = 700.0)

        val scale = CogoScale.forFirst(first, ProjectCrs.ETRS89_GEOGRAPHIC, DistanceMode.GROUND)

        scale.factor shouldBe 1.0
        scale.notices shouldBe setOf(GeometryNotice.NO_FACTOR)
    }
}

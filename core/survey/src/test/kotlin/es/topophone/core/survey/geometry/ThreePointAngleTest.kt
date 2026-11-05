package es.topophone.core.survey.geometry

import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.geometry.GeometryFixtures.number
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Ángulo en B entre las direcciones a A y a C, frente a un cálculo aparte (S026/RF-14, RF-15). */
class ThreePointAngleTest {

    private val cases = listOf("angulo_general", "angulo_recto", "angulo_casi_llano", "angulo_casi_nulo")

    private fun angle(case: String, scale: SegmentScale, swap: Boolean = false): AngleResult {
        val (a, b, c) = GeometryFixtures.vertices(case)
        return if (swap) ThreePointAngle.of(c, b, a, scale) else ThreePointAngle.of(a, b, c, scale)
    }

    @Test
    fun `S026 RF-14 el angulo, los azimuts y las distancias coinciden con la referencia en cuadricula`() {
        for (case in cases) {
            val result = angle(case, SegmentScale.Grid) as AngleResult.Angle
            val e = GeometryFixtures.angle(case, "grid")
            result.angleRad shouldBe (e.number("angulo_rad") plusOrMinus 1e-9)
            result.azimuthBaRad shouldBe (e.number("azimut_ba_rad") plusOrMinus 1e-9)
            result.azimuthBcRad shouldBe (e.number("azimut_bc_rad") plusOrMinus 1e-9)
            result.distanceBa shouldBe (e.number("distancia_ba") plusOrMinus 1e-6)
            result.distanceBc shouldBe (e.number("distancia_bc") plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S026 RF-14 las distancias de terreno usan el factor de cada lado`() {
        for (case in cases) {
            val result = angle(case, GroundScale(ProjectCrs.ETRS89_UTM30, 650.0)) as AngleResult.Angle
            val e = GeometryFixtures.angle(case, "ground")
            result.distanceBa shouldBe (e.number("distancia_ba") plusOrMinus 1e-4)
            result.distanceBc shouldBe (e.number("distancia_bc") plusOrMinus 1e-4)
            result.angleRad shouldBe (e.number("angulo_rad") plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S026 RF-14 el angulo recto mide un cuarto de vuelta y el casi llano y el casi nulo no se desestabilizan`() {
        (angle("angulo_recto", SegmentScale.Grid) as AngleResult.Angle).angleRad shouldBe (Math.PI / 2 plusOrMinus 1e-12)
        val flat = (angle("angulo_casi_llano", SegmentScale.Grid) as AngleResult.Angle).angleRad
        (flat < Math.PI && Math.PI - flat < 2e-7) shouldBe true
        val sharp = (angle("angulo_casi_nulo", SegmentScale.Grid) as AngleResult.Angle).angleRad
        (sharp > 0.0 && sharp < 0.011) shouldBe true
    }

    @Test
    fun `S026 RF-14 intercambiar A y C da el mismo angulo, siempre entre 0 y 180 grados`() {
        for (case in cases) {
            val direct = (angle(case, SegmentScale.Grid) as AngleResult.Angle).angleRad
            val swapped = (angle(case, SegmentScale.Grid, swap = true) as AngleResult.Angle).angleRad
            swapped shouldBe (direct plusOrMinus 1e-12)
            (direct in 0.0..Math.PI) shouldBe true
        }
    }

    @Test
    fun `S026 RF-15 dos puntos con las mismas coordenadas horizontales no dan angulo y se indican`() {
        val p = GeometryVertex(easting = 10.0, northing = 20.0, height = 5.0)
        val q = GeometryVertex(easting = 50.0, northing = 80.0)
        ThreePointAngle.of(p, p.copy(height = 9.0), q, SegmentScale.Grid) shouldBe
            AngleResult.Coincident(listOf(AnglePoint.A to AnglePoint.B))
        ThreePointAngle.of(q, p, p, SegmentScale.Grid) shouldBe AngleResult.Coincident(listOf(AnglePoint.B to AnglePoint.C))
        ThreePointAngle.of(p, q, p, SegmentScale.Grid) shouldBe AngleResult.Coincident(listOf(AnglePoint.A to AnglePoint.C))
        ThreePointAngle.of(p, p, p, SegmentScale.Grid) shouldBe
            AngleResult.Coincident(listOf(AnglePoint.A to AnglePoint.B, AnglePoint.B to AnglePoint.C, AnglePoint.A to AnglePoint.C))
    }

    @Test
    fun `S026 RF-14 sin posicion en algun punto no se calcula`() {
        val ok = GeometryVertex(easting = 0.0, northing = 0.0)
        ThreePointAngle.of(ok, GeometryVertex(), ok.copy(easting = 5.0), SegmentScale.Grid) shouldBe AngleResult.NoPosition
    }
}

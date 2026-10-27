package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** El punto del eje por PK y desplazamiento frente a `fixtures/ejes/` (S049/RF-4, RF-20, constitución nº 2: ≤ 1 mm). */
class AlignmentGeometryTest {
    private val points = AlignmentFixtures.points()

    @Test
    fun `S049 RF-20 cada PK y desplazamiento da el Este y el Norte esperados a menos de 1 mm`() {
        var checked = 0
        for (axis in AlignmentFixtures.axes) {
            val geometry = AlignmentGeometry(AlignmentFixtures.alignment(axis))
            for (p in points.filter { it.axis == axis }) {
                val got = geometry.pointAt(p.chainage, p.offset).shouldNotBeNull()
                hypot(got.easting - p.easting, got.northing - p.northing) shouldBe (0.0 plusOrMinus 1e-6)
                checked++
            }
        }
        (checked > 100) shouldBe true
    }

    @Test
    fun `S049 RF-20 el azimut y el radio del eje coinciden con lo esperado`() {
        for (p in points.filter { it.offset == 0.0 }) {
            val got = AlignmentGeometry(AlignmentFixtures.alignment(p.axis)).pointAt(p.chainage).shouldNotBeNull()
            Math.toDegrees(got.azimuth) shouldBe (p.azimuthDeg plusOrMinus 1e-9)
            if (p.radius.isInfinite()) {
                got.radius shouldBe Double.POSITIVE_INFINITY
            } else {
                got.radius shouldBe
                    (p.radius plusOrMinus p.radius * 1e-9)
            }
        }
    }

    @Test
    fun `S049 RF-20 el desplazamiento negativo es a la izquierda y el positivo a la derecha`() {
        val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje C")) // recta hacia el Este
        val left = geometry.pointAt(441100.0 - 441000.0, -3.5).shouldNotBeNull()
        val right = geometry.pointAt(100.0, 3.5).shouldNotBeNull()

        left.northing shouldBe (4474003.5 plusOrMinus 1e-9) // izquierda de ir al Este es el Norte
        right.northing shouldBe (4473996.5 plusOrMinus 1e-9)
        left.easting shouldBe (441100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-20 un desplazamiento 0 esta sobre el eje y el PK inicial puede no ser 0`() {
        val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje B"))

        geometry.startChainage shouldBe 1000.5
        geometry.endChainage shouldBe (1380.5 plusOrMinus 1e-9)
        val start = geometry.pointAt(1000.5).shouldNotBeNull()
        start.easting shouldBe 440500.0
        start.northing shouldBe 4474300.0
        start.easting shouldBe start.axisEasting
    }

    @Test
    fun `S049 RF-21 un PK fuera del eje no da punto`() {
        val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje A"))

        geometry.pointAt(-0.001).shouldBeNull()
        geometry.pointAt(420.001).shouldBeNull()
        geometry.pointAt(420.0).shouldNotBeNull()
        geometry.contains(420.0) shouldBe true
    }

    @Test
    fun `S049 RF-4 una clotoide de entrada empieza recta y su curvatura crece de forma lineal`() {
        val spiral = AlignmentFixtures.alignment("Eje A").elements[1]
        val half = ElementGeometry.stateAt(spiral, spiral.length / 2)

        half.curvature shouldBe (spiral.endCurvature / 2 plusOrMinus 1e-15)
        ElementGeometry.stateAt(spiral, 0.0).curvature shouldBe 0.0
        ElementGeometry.stateAt(spiral, spiral.length).curvature shouldBe (spiral.endCurvature plusOrMinus 1e-15)
    }

    @Test
    fun `S049 RF-4 el final de cada tramo es el inicio del siguiente a menos de una micra`() {
        for (axis in AlignmentFixtures.axes) {
            val elements: List<AlignmentElement> = AlignmentFixtures.alignment(axis).elements
            elements.zipWithNext().forEach { (a, b) ->
                val end = ElementGeometry.stateAt(a, a.length)
                hypot(end.easting - b.startEasting, end.northing - b.startNorthing) shouldBe (0.0 plusOrMinus 1e-6)
            }
        }
    }
}

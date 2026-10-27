package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** El PK y el desplazamiento de una posición (S049/RF-18, RF-21, constitución nº 2: ≤ 1 mm, también en clotoides). */
class AlignmentLocateTest {
    private val points = AlignmentFixtures.points()

    @Test
    fun `S049 RF-18 la posicion esperada devuelve su PK y su desplazamiento a menos de 1 mm`() {
        var checked = 0
        for (p in points) {
            val located = AlignmentGeometry(AlignmentFixtures.alignment(p.axis)).locate(p.easting, p.northing).shouldNotBeNull()
            located.chainage shouldBe (p.chainage plusOrMinus 1e-6)
            located.offset shouldBe (p.offset plusOrMinus 1e-6)
            located.distance shouldBe (abs(p.offset) plusOrMinus 1e-6)
            checked++
        }
        (checked > 100) shouldBe true
    }

    @Test
    fun `S049 RF-18 ida y vuelta con pointAt en clotoides y arcos con desplazamientos de hasta 30 m`() {
        val random = Random(49)
        for (axis in listOf("Eje A", "Eje B")) {
            val geometry = AlignmentGeometry(AlignmentFixtures.alignment(axis))
            repeat(400) {
                val chainage = random.nextDouble(geometry.startChainage + 0.5, geometry.endChainage - 0.5)
                val offset = random.nextDouble(-30.0, 30.0)
                val point = geometry.pointAt(chainage, offset).shouldNotBeNull()

                val back = geometry.locate(point.easting, point.northing).shouldNotBeNull()

                back.chainage shouldBe (chainage plusOrMinus 1e-6)
                back.offset shouldBe (offset plusOrMinus 1e-6)
            }
        }
    }

    @Test
    fun `S049 RF-21 una posicion por detras del inicio o por delante del final no tiene PK`() {
        val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje C")) // recta de 200 m hacia el Este desde 441000

        geometry.locate(440990.0, 4474002.0).shouldBeNull()
        geometry.locate(441210.0, 4474002.0).shouldBeNull()
        geometry.locate(441000.0, 4474002.0).shouldNotBeNull().chainage shouldBe (0.0 plusOrMinus 1e-9)
        geometry.locate(441200.0, 4474002.0).shouldNotBeNull().chainage shouldBe (200.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-18 el signo del desplazamiento es negativo a la izquierda y positivo a la derecha`() {
        val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje C"))

        geometry.locate(441100.0, 4474003.5).shouldNotBeNull().offset shouldBe (-3.5 plusOrMinus 1e-9)
        geometry.locate(441100.0, 4473996.5).shouldNotBeNull().offset shouldBe (3.5 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-18 junto al centro de un arco da el pie mas cercano sin fallar`() {
        val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje A"))
        val arc = geometry.pointAt(230.0)!!
        // Un punto del lado de dentro de la curva, a 100 m del eje: el radio es 150 m.
        val inside = geometry.pointAt(230.0, -100.0)!!

        geometry.locate(arc.easting, arc.northing).shouldNotBeNull().chainage shouldBe (230.0 plusOrMinus 1e-6)
        // A la derecha de una curva a la derecha es el lado de dentro: el desplazamiento positivo.
        geometry.pointAt(230.0, 100.0)!!.let {
            geometry.locate(it.easting, it.northing).shouldNotBeNull().chainage shouldBe (230.0 plusOrMinus 1e-6)
        }
        geometry.locate(inside.easting, inside.northing).shouldNotBeNull().offset shouldBe (-100.0 plusOrMinus 1e-6)
    }
}

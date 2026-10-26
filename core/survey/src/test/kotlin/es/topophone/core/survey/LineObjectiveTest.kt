package es.topophone.core.survey

import es.topophone.core.survey.LineFixtures.number
import es.topophone.core.survey.LineFixtures.optional
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El objetivo de "A la línea", "A lo largo" y "Tramo A–B" frente a lo calculado aparte (S041/RF-10, RF-11, RF-17, RF-23, RF-25). */
class LineObjectiveTest {

    private fun east(length: Double = 100.0, z: DoubleArray? = null) =
        StakeLine.create(doubleArrayOf(0.0, length), doubleArrayOf(0.0, 0.0), z)!!

    private fun objective(line: StakeLine, e: Double, n: Double, offset: Double, range: LineRange = LineRange.whole(line)) =
        LineObjectives.of(line, LineLocator(line).locate(e, n), offset, range)

    @Test
    fun `S041 RF-11 el objetivo y el error lateral salen como en pyproj con desplazamientos a ambos lados`() {
        val rows = LineFixtures.rows("objetivos.csv")

        (rows.size > 300) shouldBe true
        rows.forEach { row ->
            val line = LineFixtures.line(row.getValue("linea"))
            val o = objective(line, row.number("punta_e"), row.number("punta_n"), row.number("desplazamiento"))

            o.point.easting shouldBe (row.number("objetivo_e") plusOrMinus 1e-3)
            o.point.northing shouldBe (row.number("objetivo_n") plusOrMinus 1e-3)
            o.lateralErrorM.shouldNotBeNull() shouldBe (row.number("error_lateral") plusOrMinus 1e-3)
            o.chainageM shouldBe (row.number("pk") plusOrMinus 1e-3)
            o.position shouldBe RangePosition.WITHIN
            o.heightM?.shouldBe(row.optional("cota_pk")!! plusOrMinus 1e-6) ?: row.optional("cota_pk").shouldBeNull()
        }
    }

    @Test
    fun `S041 RF-10 A la linea es el desplazamiento 0 y el objetivo es el punto mas cercano`() {
        val line = LineFixtures.line("polilinea")
        val tip = LineLocator(line).locate(440320.0, 4474250.0)

        val o = LineObjectives.of(line, tip, 0.0)

        o.point shouldBe tip.nearest
        o.lateralErrorM.shouldNotBeNull() shouldBe tip.lateralM
        o.chainageM shouldBe tip.chainageM
        o.remainingM shouldBe (line.lengthM - tip.chainageM plusOrMinus 1e-9)
        o.alongM shouldBe tip.chainageM
    }

    @Test
    fun `S041 RF-11 estar donde se quiere da error lateral 0 y el signo dice hacia donde corregir`() {
        val line = east()

        objective(line, 40.0, -3.0, offset = 3.0).lateralErrorM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-12)
        // a 2 m a la derecha de donde se quiere estar, y a 2 m a la izquierda
        objective(line, 40.0, -5.0, offset = 3.0).lateralErrorM.shouldNotBeNull() shouldBe (2.0 plusOrMinus 1e-12)
        objective(line, 40.0, -1.0, offset = 3.0).lateralErrorM.shouldNotBeNull() shouldBe (-2.0 plusOrMinus 1e-12)
        objective(line, 40.0, 4.0, offset = -4.0).lateralErrorM.shouldNotBeNull() shouldBe (0.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-11 el objetivo con desplazamiento a la derecha queda al sur de una linea hacia el este`() {
        val o = objective(east(), 40.0, 7.0, offset = 2.0)

        o.point.easting shouldBe (40.0 plusOrMinus 1e-12)
        o.point.northing shouldBe (-2.0 plusOrMinus 1e-12)
        objective(east(), 40.0, 7.0, offset = -2.0).point.northing shouldBe (2.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-14 antes del inicio o despues del final el objetivo es el extremo y no hay error lateral`() {
        val line = east()

        val before = objective(line, -8.0, 3.0, offset = 2.0)
        val after = objective(line, 120.0, 3.0, offset = 0.0)

        before.position shouldBe RangePosition.BEFORE
        before.lateralErrorM.shouldBeNull()
        before.chainageM shouldBe 0.0
        before.point.easting shouldBe (0.0 plusOrMinus 1e-12)
        before.point.northing shouldBe (-2.0 plusOrMinus 1e-12) // el extremo, también desplazado
        after.position shouldBe RangePosition.AFTER
        after.point shouldBe PlanePoint(100.0, 0.0)
        after.remainingM shouldBe 0.0
    }

    @Test
    fun `S041 RF-23 la cota del objetivo es la interpolada en su PK y sin cota es null`() {
        objective(east(z = doubleArrayOf(100.0, 104.0)), 25.0, 2.0, 0.0).heightM shouldBe (101.0 plusOrMinus 1e-12)
        objective(east(), 25.0, 2.0, 0.0).heightM.shouldBeNull()
    }

    @Test
    fun `S041 RF-25 la llegada horizontal depende solo del error lateral y no cambia con la cota`() {
        val flat = objective(east(), 30.0, -1.0, 0.0)
        val sloped = objective(east(z = doubleArrayOf(0.0, 50.0)), 30.0, -1.0, 0.0)

        sloped.lateralErrorM shouldBe flat.lateralErrorM
        sloped.point shouldBe flat.point
    }

    @Test
    fun `S041 RF-17 dentro del tramo A-B el objetivo es el cercano y se da el PK respecto a A y lo que falta hasta B`() {
        val line = east()

        val o = objective(line, 40.0, -2.0, offset = 0.0, range = LineRange(20.0, 60.0))

        o.position shouldBe RangePosition.WITHIN
        o.point shouldBe PlanePoint(40.0, 0.0)
        o.alongM shouldBe (20.0 plusOrMinus 1e-12)
        o.remainingM shouldBe (20.0 plusOrMinus 1e-12)
        o.lateralErrorM.shouldNotBeNull() shouldBe (2.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-17 fuera del tramo A-B el objetivo es el extremo del tramo mas cercano`() {
        val line = east()
        val range = LineRange(20.0, 60.0)

        val beforeA = objective(line, 5.0, -2.0, 1.0, range)
        val afterB = objective(line, 80.0, 2.0, 1.0, range)

        beforeA.position shouldBe RangePosition.BEFORE
        beforeA.chainageM shouldBe 20.0
        beforeA.point.easting shouldBe (20.0 plusOrMinus 1e-12)
        beforeA.point.northing shouldBe (-1.0 plusOrMinus 1e-12)
        beforeA.lateralErrorM.shouldBeNull()
        beforeA.alongM shouldBe 0.0
        beforeA.remainingM shouldBe 40.0
        afterB.position shouldBe RangePosition.AFTER
        afterB.chainageM shouldBe 60.0
        afterB.remainingM shouldBe 0.0
        afterB.alongM shouldBe 40.0
    }

    @Test
    fun `S041 RF-16 con A mayor que B el tramo se recorre al reves y antes y despues se miran desde A`() {
        val line = east()
        val range = LineRange(60.0, 20.0) // invertido

        val nearA = objective(line, 70.0, 1.0, 0.0, range) // más allá de A
        val nearB = objective(line, 10.0, 1.0, 0.0, range) // más allá de B
        val inside = objective(line, 50.0, 1.0, 0.0, range)

        nearA.position shouldBe RangePosition.BEFORE
        nearA.chainageM shouldBe 60.0
        nearB.position shouldBe RangePosition.AFTER
        nearB.chainageM shouldBe 20.0
        inside.position shouldBe RangePosition.WITHIN
        inside.alongM shouldBe (10.0 plusOrMinus 1e-12)
        inside.remainingM shouldBe (30.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-10 en una linea cerrada no hay antes ni despues y el objetivo siempre es el cercano`() {
        val ring = LineFixtures.line("cerrada")

        val o = objective(ring, ring.easting(0) - 30.0, ring.northing(0) - 30.0, 0.0)

        o.position shouldBe RangePosition.WITHIN
        o.point.easting shouldBe (ring.easting(0) plusOrMinus 1e-9)
        o.lateralErrorM.shouldNotBeNull()
    }

    @Test
    fun `S041 RF-11 la distancia de la punta al objetivo es la que mira la diana`() {
        val o = objective(east(), 40.0, 7.0, offset = 2.0)

        o.distanceFrom(40.0, 7.0) shouldBe (9.0 plusOrMinus 1e-12)
    }
}

package es.topophone.core.survey

import es.topophone.core.survey.LineFixtures.number
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.jupiter.api.Test

/** El punto más cercano, el PK y la distancia lateral frente a lo calculado aparte (S041/RF-10, RF-14, RF-15, RNF-T13). */
class LineLocatorTest {

    private val positions = mapOf(
        "EN_LINEA" to LinePosition.ON_LINE,
        "ANTES_INICIO" to LinePosition.BEFORE_START,
        "DESPUES_FINAL" to LinePosition.AFTER_END,
    )

    private fun straight(length: Double = 100.0) = StakeLine.create(doubleArrayOf(0.0, length), doubleArrayOf(0.0, 0.0), null)!!

    @Test
    fun `S041 RF-10 RF-14 RF-15 el cercano el PK la distancia y el lado de cada punta salen como en pyproj a menos de 1 mm`() {
        val rows = LineFixtures.rows("consultas.csv")

        (rows.size > 400) shouldBe true
        rows.forEach { row ->
            val line = LineFixtures.line(row.getValue("linea"))
            val l = LineLocator(line).locate(row.number("punta_e"), row.number("punta_n"))

            withClue(row.toString()) { l.distanceM shouldBe (row.number("distancia") plusOrMinus 1e-3) }
            l.nearest.easting shouldBe (row.number("cercano_e") plusOrMinus 1e-3)
            l.nearest.northing shouldBe (row.number("cercano_n") plusOrMinus 1e-3)
            l.chainageM shouldBe (row.number("pk") plusOrMinus 1e-3)
            l.distanceM shouldBe (row.number("distancia") plusOrMinus 1e-3)
            l.lateralM shouldBe (row.number("lateral") plusOrMinus 1e-3)
            l.position shouldBe positions.getValue(row.getValue("posicion"))
            l.remainingM shouldBe (row.number("falta") plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S041 RF-10 los casos cubren ambos lados antes y despues y todas las lineas`() {
        val results = LineFixtures.rows("consultas.csv").map {
            LineLocator(LineFixtures.line(it.getValue("linea"))).locate(it.number("punta_e"), it.number("punta_n"))
        }

        (results.count { it.lateralM > 0.01 } >= 20) shouldBe true
        (results.count { it.lateralM < -0.01 } >= 20) shouldBe true
        (results.count { it.position == LinePosition.BEFORE_START } >= 5) shouldBe true
        (results.count { it.position == LinePosition.AFTER_END } >= 5) shouldBe true
    }

    @Test
    fun `S041 RF-10 en una recta hacia el este la derecha es el sur y la distancia lateral lleva el signo`() {
        val locator = LineLocator(straight())

        locator.locate(40.0, -3.0).lateralM shouldBe (3.0 plusOrMinus 1e-12)
        locator.locate(40.0, 2.0).lateralM shouldBe (-2.0 plusOrMinus 1e-12)
        locator.locate(40.0, 0.0).lateralM shouldBe 0.0
        locator.locate(40.0, -3.0).chainageM shouldBe (40.0 plusOrMinus 1e-12)
        locator.locate(40.0, -3.0).remainingM shouldBe (60.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-14 antes del inicio y despues del final el cercano es el extremo y no hay lado`() {
        val locator = LineLocator(straight())

        val before = locator.locate(-4.0, 3.0)
        val after = locator.locate(107.0, -1.0)

        before.position shouldBe LinePosition.BEFORE_START
        before.nearest shouldBe PlanePoint(0.0, 0.0)
        before.distanceM shouldBe (5.0 plusOrMinus 1e-12)
        before.lateralM shouldBe 0.0
        before.chainageM shouldBe 0.0
        after.position shouldBe LinePosition.AFTER_END
        after.nearest shouldBe PlanePoint(100.0, 0.0)
        after.remainingM shouldBe 0.0
        after.lateralM shouldBe 0.0
    }

    @Test
    fun `S041 RF-14 en un vertice interior no hay antes ni despues aunque el cercano sea el vertice`() {
        val bent = StakeLine.create(doubleArrayOf(0.0, 10.0, 10.0), doubleArrayOf(0.0, 0.0, 10.0), null)!!

        val outside = LineLocator(bent).locate(14.0, -3.0) // fuera de la esquina: el cercano es el vertice (10, 0)

        outside.nearest shouldBe PlanePoint(10.0, 0.0)
        outside.position shouldBe LinePosition.ON_LINE
        outside.distanceM shouldBe (5.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-14 una linea cerrada no tiene extremos aunque la punta quede lejos del primer vertice`() {
        val square = StakeLine.create(doubleArrayOf(0.0, 10.0, 10.0, 0.0), doubleArrayOf(0.0, 0.0, 10.0, 10.0), null, closed = true)!!

        val l = LineLocator(square).locate(-5.0, -5.0)

        l.position shouldBe LinePosition.ON_LINE
        l.nearest shouldBe PlanePoint(0.0, 0.0)
        l.distanceM shouldBe (hypotenuse(5.0, 5.0) plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-15 en una cerrada el tramo de cierre cuenta en el PK y la derecha sigue el sentido de la linea`() {
        val square = StakeLine.create(doubleArrayOf(0.0, 10.0, 10.0, 0.0), doubleArrayOf(0.0, 0.0, 10.0, 10.0), null, closed = true)!!
        val locator = LineLocator(square)

        val onClosing = locator.locate(-2.0, 4.0) // el tramo de vuelta (0,10)→(0,0) va hacia el sur: su derecha es el oeste

        onClosing.segment shouldBe 3
        onClosing.chainageM shouldBe (36.0 plusOrMinus 1e-12)
        onClosing.lateralM shouldBe (2.0 plusOrMinus 1e-12)
        onClosing.remainingM shouldBe (4.0 plusOrMinus 1e-12)
        locator.locate(5.0, 3.0).lateralM shouldBe (-3.0 plusOrMinus 1e-12) // dentro del cuadrado: a la izquierda
    }

    @Test
    fun `S041 RF-10 con dos tramos a la misma distancia gana el primero`() {
        val v = StakeLine.create(doubleArrayOf(-10.0, 0.0, 10.0), doubleArrayOf(0.0, 0.0, 0.0), null)!!

        LineLocator(v).locate(0.0, 5.0).segment shouldBe 0 // empata con el segundo: el vertice (0, 0) pertenece a los dos
    }

    @Test
    fun `S041 RNF-T13 la rejilla da lo mismo que recorrer todos los tramos con una linea de 20000 vertices`() {
        val line = zigzag(20_000, Random(7))
        val locator = LineLocator(line)
        val rng = Random(11)

        repeat(3_000) {
            val e = rng.nextDouble(-500.0, line.easting(line.vertexCount - 1) + 500.0)
            val n = rng.nextDouble(-500.0, 800.0)
            val grid = locator.locate(e, n)
            val all = locator.locateExhaustive(e, n)

            grid.distanceM shouldBe (all.distanceM plusOrMinus 1e-9)
            grid.chainageM shouldBe (all.chainageM plusOrMinus 1e-6)
            grid.lateralM shouldBe (all.lateralM plusOrMinus 1e-9)
            grid.position shouldBe all.position
        }
    }

    @Test
    fun `S041 RNF-T13 empezar por el tramo de la epoca anterior no cambia el resultado`() {
        val line = zigzag(5_000, Random(3))
        val locator = LineLocator(line)
        val rng = Random(5)

        repeat(1_000) {
            val e = rng.nextDouble(0.0, 5_000.0 * 3)
            val n = rng.nextDouble(-50.0, 80.0)
            val without = locator.locate(e, n)
            val with = locator.locate(e, n, hint = rng.nextInt(line.segmentCount))

            with.segment shouldBe without.segment
            with.distanceM shouldBe (without.distanceM plusOrMinus 1e-12)
        }
    }

    @Test
    fun `S041 RNF-T13 un tramo muy largo y una punta lejos de la rejilla dan el mismo resultado que recorrerlo todo`() {
        val line = StakeLine.create(doubleArrayOf(0.0, 50_000.0, 50_000.0), doubleArrayOf(0.0, 40_000.0, 100.0), null)!!
        val locator = LineLocator(line)

        listOf(1_000.0 to 700.0, 49_990.0 to 30_000.0, -1e6 to 5e5, 25_000.0 to 20_010.0).forEach { (e, n) ->
            locator.locate(e, n).distanceM shouldBe (locator.locateExhaustive(e, n).distanceM plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S041 RNF-T13 una linea de 100000 vertices se prepara y se consulta a 10 Hz sobrados`() {
        val line = zigzag(100_000, Random(1))
        val prepared = System.nanoTime()
        val locator = LineLocator(line)
        val prepareMs = (System.nanoTime() - prepared) / 1e6
        val rng = Random(2)
        repeat(200) { locator.locate(rng.nextDouble(0.0, 300_000.0), rng.nextDouble(-20.0, 40.0)) } // calentar
        val started = System.nanoTime()
        repeat(1_000) { locator.locate(rng.nextDouble(0.0, 300_000.0), rng.nextDouble(-20.0, 40.0)) }
        val perQueryMs = (System.nanoTime() - started) / 1e6 / 1_000

        (prepareMs < 1_000.0) shouldBe true
        (perQueryMs < 16.0) shouldBe true
    }

    private fun hypotenuse(a: Double, b: Double) = kotlin.math.hypot(a, b)

    /** Una polilínea de [n] vértices en zigzag hacia el este, con ruido, para que haya muchos tramos cerca. */
    private fun zigzag(n: Int, rng: Random): StakeLine {
        val e = DoubleArray(n) { it * 3.0 + rng.nextDouble(-0.5, 0.5) }
        val north = DoubleArray(n) { 12.0 * sin(it * PI / 7.0) + 5.0 * cos(it * 0.3) + rng.nextDouble(-0.5, 0.5) }
        return StakeLine.create(e, north, null)!!
    }
}

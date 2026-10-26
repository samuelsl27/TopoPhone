package es.topophone.core.survey

import es.topophone.core.model.point.LineInputError
import es.topophone.core.survey.LineFixtures.number
import es.topophone.core.survey.LineFixtures.optional
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El tramo A–B, "Invertir", "Mi posición", los intervalos y sus validaciones (S041/RF-16 a RF-21). */
class LineSeriesTest {

    private fun east(length: Double = 100.0) = StakeLine.create(doubleArrayOf(0.0, length), doubleArrayOf(0.0, 0.0), null)!!

    private fun series(line: StakeLine, a: Double, b: Double, step: Double, offset: Double = 0.0) =
        LineSeries(line, LineRange(a, b), step, offset)

    private fun invalid(check: LineCheck<*>) = check.shouldBeInstanceOf<LineCheck.Invalid>().error

    @Test
    fun `S041 RF-18 las series calculadas aparte salen con los mismos PK puntos y cotas, a ambos lados y al reves`() {
        val rows = LineFixtures.rows("series.csv")
        val cases = rows.groupBy { listOf(it["linea"], it["pk_a"], it["pk_b"], it["intervalo"], it["desplazamiento"]) }

        (cases.size >= 20) shouldBe true
        cases.forEach { (key, expected) ->
            val line = LineFixtures.line(key[0]!!)
            val s = series(line, key[1]!!.toDouble(), key[2]!!.toDouble(), key[3]!!.toDouble(), key[4]!!.toDouble())

            s.size shouldBe expected.size
            expected.forEach { row ->
                val p = s[row.getValue("i").toInt()]
                p.chainageM shouldBe (row.number("pk") plusOrMinus 1e-6)
                p.point.easting shouldBe (row.number("este") plusOrMinus 1e-3)
                p.point.northing shouldBe (row.number("norte") plusOrMinus 1e-3)
                val z = row.optional("cota")
                if (z == null) p.heightM.shouldBeNull() else p.heightM!! shouldBe (z plusOrMinus 1e-6)
            }
        }
    }

    @Test
    fun `S041 RF-18 B siempre se incluye aunque no caiga en un multiplo del intervalo`() {
        val s = series(east(), 0.0, 25.0, 10.0)

        s.toList().map { it.chainageM } shouldBe listOf(0.0, 10.0, 20.0, 25.0)
    }

    @Test
    fun `S041 RF-18 con B justo en un multiplo no se repite`() {
        series(east(), 0.0, 30.0, 10.0).toList().map { it.chainageM } shouldBe listOf(0.0, 10.0, 20.0, 30.0)
        series(east(), 5.0, 25.0, 10.0).toList().map { it.chainageM } shouldBe listOf(5.0, 15.0, 25.0)
    }

    @Test
    fun `S041 RF-19 con un intervalo igual o mayor que el tramo solo salen A y B`() {
        series(east(), 10.0, 40.0, 30.0).toList().map { it.chainageM } shouldBe listOf(10.0, 40.0)
        series(east(), 10.0, 40.0, 500.0).toList().map { it.chainageM } shouldBe listOf(10.0, 40.0)
    }

    @Test
    fun `S041 RF-16 con A mayor que B la serie va de A hacia B en sentido inverso`() {
        series(east(), 47.5, 5.0, 10.0).toList().map { it.chainageM } shouldBe listOf(47.5, 37.5, 27.5, 17.5, 7.5, 5.0)
    }

    @Test
    fun `S041 RF-18 con desplazamiento los puntos quedan a esa distancia a la derecha o a la izquierda`() {
        val right = series(east(), 0.0, 20.0, 10.0, offset = 2.5).toList()
        val left = series(east(), 0.0, 20.0, 10.0, offset = -2.5).toList()

        right.map { it.point.northing } shouldBe List(3) { -2.5 }
        left.map { it.point.northing } shouldBe List(3) { 2.5 }
        right.map { it.point.easting } shouldBe listOf(0.0, 10.0, 20.0)
    }

    @Test
    fun `S041 RF-20 los puntos se piden por su numero y el ultimo es B`() {
        val s = series(east(), 0.0, 25.0, 10.0)

        s[0].index shouldBe 0
        s[3].index shouldBe 3
        s.chainageOf(s.size - 1) shouldBe 25.0
    }

    @Test
    fun `S041 RF-20 un intervalo diminuto no construye la lista sino que da el tamano y los puntos al pedirlos`() {
        val s = series(east(), 0.0, 100.0, 0.001)

        s.size shouldBe 100_001
        s[50_000].chainageM shouldBe (50.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S041 RF-21 un valor que no es un numero no se aplica`() {
        invalid(LineInputs.offset("abc")) shouldBe LineInputError.NOT_A_NUMBER
        invalid(LineInputs.offset("")) shouldBe LineInputError.NOT_A_NUMBER
        invalid(LineInputs.interval("1,2,3")) shouldBe LineInputError.NOT_A_NUMBER
        invalid(LineInputs.range(east(), "x", "10")) shouldBe LineInputError.NOT_A_NUMBER
        invalid(LineInputs.range(east(), "10", "NaN")) shouldBe LineInputError.NOT_A_NUMBER
        invalid(LineInputs.interval("Infinity")) shouldBe LineInputError.NOT_A_NUMBER
    }

    @Test
    fun `S041 RF-21 un PK fuera de la linea no se aplica`() {
        invalid(LineInputs.range(east(), "-1", "50")) shouldBe LineInputError.OUT_OF_LINE
        invalid(LineInputs.range(east(), "10", "100,5")) shouldBe LineInputError.OUT_OF_LINE
        LineInputs.range(east(), "0", "100").shouldBeInstanceOf<LineCheck.Valid<LineRange>>()
    }

    @Test
    fun `S041 RF-21 A y B iguales no se aplican`() {
        invalid(LineInputs.range(east(), "40", "40.0")) shouldBe LineInputError.SAME_PK
    }

    @Test
    fun `S041 RF-21 un intervalo de 0 o negativo no se aplica`() {
        invalid(LineInputs.interval("0")) shouldBe LineInputError.INTERVAL_NOT_POSITIVE
        invalid(LineInputs.interval("-5")) shouldBe LineInputError.INTERVAL_NOT_POSITIVE
        LineInputs.interval("0,5").shouldBeInstanceOf<LineCheck.Valid<Double>>().value shouldBe 0.5
    }

    @Test
    fun `S041 RF-11 el desplazamiento admite coma o punto decimal y signo`() {
        LineInputs.offset("-2,5").shouldBeInstanceOf<LineCheck.Valid<Double>>().value shouldBe -2.5
        LineInputs.offset(" 3.25 ").shouldBeInstanceOf<LineCheck.Valid<Double>>().value shouldBe 3.25
        LineInputs.offset("0").shouldBeInstanceOf<LineCheck.Valid<Double>>().value shouldBe 0.0
    }

    @Test
    fun `S041 RF-16 Invertir cambia A y B entre si`() {
        LineRange(10.0, 80.0).inverted() shouldBe LineRange(80.0, 10.0)
        LineRange(10.0, 80.0).inverted().inverted() shouldBe LineRange(10.0, 80.0)
    }

    @Test
    fun `S041 RF-16 Mi posicion pone como A el PK del punto mas cercano a la punta`() {
        val line = east()
        val here = LineLocator(line).locate(37.5, -4.0)

        LineInputs.here(here) shouldBe (37.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S041 RF-16 el inicio y el final de la linea son A y B por defecto`() {
        val line = LineFixtures.line("polilinea")

        LineRange.whole(line) shouldBe LineRange(0.0, line.lengthM)
    }
}

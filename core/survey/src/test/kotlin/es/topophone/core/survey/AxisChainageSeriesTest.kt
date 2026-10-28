package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La lista de PK del replanteo por eje frente a `fixtures/ejes/replanteo/esperado-series.csv` (S054/RF-2 a RF-4). */
class AxisChainageSeriesTest {
    private val cases = SurveyFixtures.rows("esperado-series.csv", "ejes/replanteo").groupBy { it[0] }

    private fun seriesOf(rows: List<List<String>>): AxisChainageSeries {
        val head = rows.first()
        val alignment = AlignmentFixtures.alignment(head[1])
        val keys = if (head[5] == "si") KeyStations.of(alignment.elements, alignment.profile).map { it.chainageM } else null
        return AxisChainageSeries(
            startM = head[2].toDouble(),
            endM = head[3].takeIf { it.isNotEmpty() }?.toDouble(),
            intervalM = head[4].takeIf { it.isNotEmpty() }?.toDouble(),
            keyStations = keys,
            axisEndM = AlignmentGeometry(alignment).endChainage,
        )
    }

    @Test
    fun `S054 RF-2 a RF-4 cada caso da los PK esperados en orden`() {
        cases.size shouldBe 11
        for ((name, rows) in cases) {
            val series = seriesOf(rows)

            series.size shouldBe rows.size
            (0 until series.size).map { series.chainageOf(it) }.zip(rows.map { it[7].toDouble() }).forEach { (got, want) ->
                got shouldBe (want plusOrMinus 1e-9)
            }
            check(name.isNotEmpty())
        }
    }

    @Test
    fun `S054 RF-2 sin intervalo solo se replantea el PK inicial`() {
        val series = AxisChainageSeries(100.0, null, null, null, 420.0)

        series.size shouldBe 1
        series.chainageOf(0) shouldBe 100.0
        series.next(0) shouldBe 0
    }

    @Test
    fun `S054 RF-3 siguiente y anterior no pasan de los extremos`() {
        val series = AxisChainageSeries(0.0, 420.0, 100.0, null, 420.0) // 0, 100, 200, 300, 400, 420

        series.previous(0) shouldBe 0
        series.next(0) shouldBe 1
        series.next(4) shouldBe 5
        series.next(5) shouldBe 5
        series.previous(5) shouldBe 4
    }

    @Test
    fun `S054 RF-3 el intervalo que se pasa del PK final acaba en el PK final sin salirse`() {
        val series = AxisChainageSeries(0.0, 100.0, 70.0, null, 420.0)

        (0 until series.size).map { series.chainageOf(it) } shouldBe listOf(0.0, 70.0, 100.0)
    }

    @Test
    fun `S054 RF-3 sin PK final el intervalo llega al final del eje`() {
        val series = AxisChainageSeries(300.0, null, 60.0, null, 420.0)

        (0 until series.size).map { series.chainageOf(it) } shouldBe listOf(300.0, 360.0, 420.0)
    }

    @Test
    fun `S054 RF-4 un punto singular a menos de 1 mm de uno del intervalo es el mismo punto`() {
        val series = AxisChainageSeries(0.0, 300.0, 100.0, listOf(100.0004, 150.0), 420.0)

        (0 until series.size).map { series.chainageOf(it) } shouldBe listOf(0.0, 100.0, 150.0, 200.0, 300.0)
    }

    @Test
    fun `S054 RF-3 una serie larga con intervalo diminuto no construye la lista y se consulta por posicion`() {
        val series = AxisChainageSeries(0.0, 50_000.0, 0.01, listOf(12_345.678), 50_000.0)

        series.size shouldBe 5_000_002
        series.chainageOf(0) shouldBe 0.0
        series.chainageOf(series.size - 1) shouldBe 50_000.0
        series.chainageOf(1_234_568) shouldBe (12_345.678 plusOrMinus 1e-9)
        series.chainageOf(1_234_567) shouldBe (12_345.67 plusOrMinus 1e-9)
        series.chainageOf(1_234_569) shouldBe (12_345.68 plusOrMinus 1e-9)
    }
}

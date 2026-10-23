package es.topophone.core.survey

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * Media, desviación típica y estabilidad de un promediado largo frente a pyproj y `math.fsum` (S017/RF-3 a RF-5, RF-12;
 * constitución nº 2). La tolerancia exigida es de 0,1 mm; con las mismas épocas redondeadas sobran seis órdenes.
 */
class AverageStatsTest {

    private val scenarios = listOf("promediado-60min", "promediado-canarias")

    private fun epochs(name: String) = SurveyFixtures.rows("$name.csv", "promediado")

    private fun expected(name: String) = SurveyFixtures.rows("$name.esperado.csv", "promediado")

    private fun double(cell: String): Double? = cell.takeIf { it.isNotEmpty() }?.toDouble()

    @Test
    fun `S017 RF-3 la media y las desviaciones tipicas coinciden con el calculo aparte a menos de 0,1 mm`() {
        for (name in scenarios) {
            val rows = epochs(name)
            val stats = AverageStats(epochPeriodS = 0.1)
            var next = 0
            for (checkpoint in expected(name)) {
                val n = checkpoint[0].toInt()
                while (next < n) {
                    stats.add(rows[next][4].toDouble(), rows[next][5].toDouble(), rows[next][3].toDouble())
                    next++
                }
                val snapshot = stats.snapshot().shouldNotBeNull()
                snapshot.used shouldBe n
                snapshot.timeS shouldBe (checkpoint[1].toDouble() plusOrMinus 1e-9)
                snapshot.meanEast shouldBe (checkpoint[2].toDouble() plusOrMinus 1e-6)
                snapshot.meanNorth shouldBe (checkpoint[3].toDouble() plusOrMinus 1e-6)
                snapshot.meanHeight shouldBe (checkpoint[4].toDouble() plusOrMinus 1e-6)
                val devH = double(checkpoint[5])
                val devV = double(checkpoint[6])
                if (devH == null || devV == null) {
                    snapshot.deviationHorizontalM.shouldBeNull()
                    snapshot.deviationVerticalM.shouldBeNull()
                } else {
                    snapshot.deviationHorizontalM.shouldNotBeNull() shouldBe (devH plusOrMinus 1e-6)
                    snapshot.deviationVerticalM.shouldNotBeNull() shouldBe (devV plusOrMinus 1e-6)
                }
            }
        }
    }

    @Test
    fun `S017 RF-4 la estabilidad es lo que se ha movido la media en el ultimo minuto y solo existe pasado ese minuto`() {
        for (name in scenarios) {
            val rows = epochs(name)
            val stats = AverageStats(epochPeriodS = 0.1)
            var next = 0
            for (checkpoint in expected(name)) {
                val n = checkpoint[0].toInt()
                while (next < n) {
                    stats.add(rows[next][4].toDouble(), rows[next][5].toDouble(), rows[next][3].toDouble())
                    next++
                }
                val snapshot = stats.snapshot().shouldNotBeNull()
                val stabilityH = double(checkpoint[7])
                val stabilityV = double(checkpoint[8])
                if (stabilityH == null || stabilityV == null) {
                    snapshot.stabilityHorizontalM.shouldBeNull()
                    snapshot.stabilityVerticalM.shouldBeNull()
                } else {
                    snapshot.stabilityHorizontalM.shouldNotBeNull() shouldBe (stabilityH plusOrMinus 1e-6)
                    snapshot.stabilityVerticalM.shouldNotBeNull() shouldBe (stabilityV plusOrMinus 1e-6)
                }
            }
        }
    }

    @Test
    fun `S017 RF-4 con 10 Hz la primera estabilidad llega con la epoca 601 y con 1 Hz con la 61`() {
        val tenHertz = AverageStats(epochPeriodS = 0.1)
        repeat(600) { tenHertz.add(it.toDouble(), 0.0, 0.0) }
        tenHertz.snapshot().shouldNotBeNull().stabilityHorizontalM.shouldBeNull()
        tenHertz.add(600.0, 0.0, 0.0)
        tenHertz.snapshot().shouldNotBeNull().stabilityHorizontalM.shouldNotBeNull()

        val oneHertz = AverageStats(epochPeriodS = 1.0)
        repeat(60) { oneHertz.add(it.toDouble(), 0.0, 0.0) }
        oneHertz.snapshot().shouldNotBeNull().stabilityHorizontalM.shouldBeNull()
        oneHertz.add(60.0, 0.0, 0.0)
        oneHertz.snapshot().shouldNotBeNull().stabilityHorizontalM.shouldNotBeNull()
    }

    @Test
    fun `S017 RF-4 una rampa de 1 m por epoca a 1 Hz mueve la media 30 m en un minuto y la altura en vertical`() {
        val stats = AverageStats(epochPeriodS = 1.0)
        repeat(200) { stats.add(east = it.toDouble(), north = 2.0 * it, height = -3.0 * it) }

        val snapshot = stats.snapshot().shouldNotBeNull()
        // La media tras n épocas de 0 a n − 1 es (n − 1) / 2: la de ahora (n = 200) frente a la de hace 60 (n = 140).
        snapshot.stabilityHorizontalM.shouldNotBeNull() shouldBe (hypot(30.0, 60.0) plusOrMinus 1e-9)
        snapshot.stabilityVerticalM.shouldNotBeNull() shouldBe (90.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S017 RF-3 con menos de 2 epocas no hay desviacion tipica y sin epocas no hay nada`() {
        val stats = AverageStats(epochPeriodS = 0.1)
        stats.snapshot().shouldBeNull()

        stats.add(10.0, 20.0, 30.0)
        val one = stats.snapshot().shouldNotBeNull()
        one.used shouldBe 1
        one.meanEast shouldBe 10.0
        one.deviationHorizontalM.shouldBeNull()
        one.deviationVerticalM.shouldBeNull()

        stats.add(10.0, 20.0, 30.0)
        val two = stats.snapshot().shouldNotBeNull()
        two.deviationHorizontalM shouldBe 0.0
        two.deviationVerticalM shouldBe 0.0
    }

    @Test
    fun `S017 RF-3 la desviacion horizontal es la raiz de la suma de los cuadrados de las de Este y Norte`() {
        val stats = AverageStats(epochPeriodS = 1.0)
        // Este: 0 y 6 (σ muestral 4,2426...); Norte: 0 y 8 (σ muestral 5,6568...).
        stats.add(0.0, 0.0, 0.0)
        stats.add(6.0, 8.0, 2.0)

        val snapshot = stats.snapshot().shouldNotBeNull()
        snapshot.deviationHorizontalM.shouldNotBeNull() shouldBe (hypot(6.0 / Math.sqrt(2.0), 8.0 / Math.sqrt(2.0)) plusOrMinus 1e-12)
        snapshot.deviationVerticalM.shouldNotBeNull() shouldBe (2.0 / Math.sqrt(2.0) plusOrMinus 1e-12)
    }

    @Test
    fun `S017 RF-5 el grafico dibuja como maximo 2000 epocas repartidas por igual alrededor de la media`() {
        val rows = epochs("promediado-60min")
        val stats = AverageStats(epochPeriodS = 0.1)
        rows.forEach { stats.add(it[4].toDouble(), it[5].toDouble(), it[3].toDouble()) }

        val plot = stats.dispersion()
        val mean = stats.snapshot().shouldNotBeNull()

        plot.count shouldBe 2000
        plot.eastM shouldHaveSize 2000
        // Una de cada 18 (36.000 / 2.000), a partir de la primera, como diferencia con la media actual.
        plot.eastM[0].toDouble() shouldBe (rows[0][4].toDouble() - mean.meanEast plusOrMinus 1e-6)
        plot.northM[1].toDouble() shouldBe (rows[18][5].toDouble() - mean.meanNorth plusOrMinus 1e-6)
        plot.eastM[1999].toDouble() shouldBe (rows[1999 * 18][4].toDouble() - mean.meanEast plusOrMinus 1e-6)
    }

    @Test
    fun `S017 RF-5 con pocas epocas se dibujan todas`() {
        val stats = AverageStats(epochPeriodS = 0.1)
        repeat(10) { stats.add(it * 0.001, 0.0, 0.0) }

        stats.dispersion().count shouldBe 10
        stats.dispersion(maxPoints = 4).count shouldBe 4
    }

    @Test
    fun `S017 RF-5 la escala abarca todas las epocas, con un minimo de 1 cm y en 1, 2 o 5 por una potencia de 10`() {
        fun scaleFor(radiusM: Double): Double {
            val stats = AverageStats(epochPeriodS = 1.0)
            stats.add(-radiusM, 0.0, 0.0)
            stats.add(radiusM, 0.0, 0.0)
            return stats.dispersion().scaleM
        }

        scaleFor(0.002) shouldBe 0.01
        scaleFor(0.0099) shouldBe 0.01
        scaleFor(0.0123) shouldBe 0.02
        scaleFor(0.034) shouldBe 0.05
        scaleFor(0.34) shouldBe 0.5
        scaleFor(0.9) shouldBe 1.0
        scaleFor(1.2) shouldBe 2.0
        scaleFor(7.0) shouldBe 10.0
    }

    @Test
    fun `S017 RF-2 el tiempo promediado es el numero de epocas por el periodo`() {
        val stats = AverageStats(epochPeriodS = 0.2)
        repeat(25) { stats.add(0.0, 0.0, 0.0) }

        stats.snapshot().shouldNotBeNull().timeS shouldBe (5.0 plusOrMinus 1e-9)
    }
}

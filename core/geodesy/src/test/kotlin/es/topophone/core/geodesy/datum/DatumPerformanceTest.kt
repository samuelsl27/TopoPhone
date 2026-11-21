package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.Test

/** Transformar 100.000 puntos con rejilla tarda menos de 2 s (S033/RNF: rendimiento; RNF-T13). */
class DatumPerformanceTest {

    private val pointCount = 100_000

    private val grid = GridShiftTransformation(GridShiftVector.GRID_NAME, ClasspathGridSource("/shift/"), TransformationInfo("SPED2ETV2"))

    /** 100.000 puntos distintos en el interior de la Península, dentro de la rejilla. */
    private val points: List<GeodeticPoint> = List(pointCount) { i ->
        GeodeticPoint(37.6 + (i % 400) * 0.01, -7.0 + ((i / 400) % 250) * 0.02, 100.0 + i % 50)
    }

    /** Ejecuta [transformation] sobre todos los puntos tras un calentamiento y devuelve (milisegundos, resultados correctos). */
    private fun time(transformation: (GeodeticPoint) -> TransformResult): Pair<Long, Int> {
        points.take(10_000).forEach { transformation(it) } // calentamiento de la JVM
        var ok = 0
        val nanos = measureNanoTime { points.forEach { if (transformation(it) is TransformResult.Ok) ok++ } }
        return nanos / 1_000_000 to ok
    }

    @Test
    fun `S033 RNF-T13 la rejilla directa de 100000 puntos tarda menos de 2 s`() {
        val (ms, ok) = time(grid::forward)

        ok shouldBeGreaterThan pointCount * 99 / 100
        (ms < 2_000) shouldBe true
    }

    @Test
    fun `S033 RNF-T13 la rejilla inversa de 100000 puntos tarda menos de 2 s`() {
        val (ms, ok) = time(grid::inverse)

        ok shouldBeGreaterThan pointCount * 99 / 100
        (ms < 2_000) shouldBe true
    }

    @Test
    fun `S033 RNF-T13 una cadena de tres pasos con rejilla de 100000 puntos tarda menos de 2 s`() {
        val chain = ChainVector.all().first { it.steps.size == 3 }.chain()

        val (ms, ok) = time(chain::forward)

        ok shouldBeGreaterThan pointCount * 99 / 100
        (ms < 2_000) shouldBe true
    }

    @Test
    fun `S033 RNF-T13 la cadena de tres pasos inversa de 100000 puntos tarda menos de 2 s`() {
        val chain = ChainVector.all().first { it.steps.size == 3 }.chain()

        val (ms, ok) = time(chain::inverse)

        ok shouldBeGreaterThan pointCount * 99 / 100
        (ms < 2_000) shouldBe true
    }

    @Test
    fun `S033 RNF-T13 100000 puntos con Helmert y con Molodensky-Badekas tardan menos de 2 s`() {
        val helmert = HelmertVector.all().first { it.case == "grande_7p_pv" }.transformation()
        val mb = MolodenskyBadekasVector.all().first { it.case == "mb_pivote_iberia_pv" }.transformation()

        val (helmertMs, helmertOk) = time(helmert::forward)
        val (mbMs, mbOk) = time(mb::inverse)

        helmertOk shouldBe pointCount
        mbOk shouldBe pointCount
        (helmertMs < 2_000 && mbMs < 2_000) shouldBe true
    }
}

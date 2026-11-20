package es.topophone.core.geodesy.projection

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.system.measureNanoTime
import org.junit.jupiter.api.Test

/** Convertir 100.000 puntos tarda menos de 1 s con cualquiera de los diez métodos (S032/RNF: rendimiento; RNF-T13). */
class ProjectionPerformanceTest {

    private val pointCount = 100_000
    private val limitNanos = 1_000_000_000L

    /** Un SC por método y un punto dentro de su ámbito, de los vectores de pyproj. */
    private val bases: List<PrimeMeridianVector> =
        PrimeMeridianVector.all().groupBy { it.setup.parameters.method }.values.map { it.first() }

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    /** 100.000 puntos distintos a menos de 0,2° de uno de los vectores (siempre con imagen). */
    private fun pointsAround(base: PrimeMeridianVector): List<Pair<Double, Double>> = List(pointCount) { i ->
        val dLat = ((i % 400) - 200) * 5e-4
        val dLon = (((i / 400) % 250) - 125) * 8e-4
        (base.latitudeDeg + dLat).coerceIn(-89.5, 89.5) to base.longitudeDeg + dLon
    }

    @Test
    fun `S032 RNF-T13 hay un SC por cada uno de los diez metodos`() {
        bases.map { it.setup.parameters.method }.toSet().size shouldBe 10
    }

    @Test
    fun `S032 RNF-T13 proyectar 100000 puntos tarda menos de 1 s con cualquier metodo`() {
        val slow = bases.mapNotNull { base ->
            val projection = projectionOf(base.setup)
            val points = pointsAround(base)
            points.take(10_000).forEach { (lat, lon) -> projection.forward(lat, lon) } // calentamiento de la JVM
            var checksum = 0.0
            val nanos = measureNanoTime { points.forEach { (lat, lon) -> checksum += projection.forward(lat, lon)?.eastingM ?: 0.0 } }
            if (nanos > limitNanos || checksum == 0.0) "${base.setup.parameters.method}: ${nanos / 1_000_000} ms" else null
        }

        slow.shouldBeEmpty()
    }

    @Test
    fun `S032 RNF-T13 desproyectar 100000 puntos tarda menos de 1 s con cualquier metodo`() {
        val slow = bases.mapNotNull { base ->
            val projection = projectionOf(base.setup)
            val projected = pointsAround(base).map { (lat, lon) -> projection.forward(lat, lon)!! }
            projected.take(10_000).forEach { projection.inverse(it.eastingM, it.northingM) } // calentamiento de la JVM
            var checksum = 0.0
            val nanos = measureNanoTime {
                projected.forEach { checksum += projection.inverse(it.eastingM, it.northingM)?.latitudeDeg ?: 0.0 }
            }
            if (nanos > limitNanos || checksum == 0.0) "${base.setup.parameters.method}: ${nanos / 1_000_000} ms" else null
        }

        slow.shouldBeEmpty()
    }

    @Test
    fun `S032 RNF-T13 calcular el factor de escala y la convergencia de 100000 puntos tarda menos de 1 s con cualquier metodo`() {
        val slow = bases.mapNotNull { base ->
            val projection = projectionOf(base.setup)
            val points = pointsAround(base)
            points.take(10_000).forEach { (lat, lon) -> projection.factors(lat, lon) } // calentamiento de la JVM
            var checksum = 0.0
            val nanos = measureNanoTime { points.forEach { (lat, lon) -> checksum += projection.factors(lat, lon)?.scale ?: 0.0 } }
            if (nanos > limitNanos || checksum == 0.0) "${base.setup.parameters.method}: ${nanos / 1_000_000} ms" else null
        }

        slow.shouldBeEmpty()
    }
}

package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Utm
import es.topophone.core.geodesy.WebMercator
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * Ida y vuelta (geográficas → proyectadas → geográficas) de los diez métodos de S032 en las rejillas de todos los ficheros de vectores, y de
 * la Transversa de Mercator y Web Mercator de siempre, con una diferencia ≤ 0,1 mm dentro del ámbito de cada proyección (S032/RF-18).
 */
class ProjectionRoundTripTest {

    private data class Sample(val case: String, val setup: ProjectionSetup, val latitudeDeg: Double, val longitudeDeg: Double)

    private val samples: List<Sample> = buildList {
        LccVector.all().forEach { add(Sample("lcc ${it.case}", it.setup, it.latitudeDeg, it.longitudeDeg)) }
        MercatorVector.all().forEach { add(Sample("mercator ${it.case}", it.setup, it.latitudeDeg, it.longitudeDeg)) }
        OmercVector.all().forEach { add(Sample("omerc ${it.case}", it.setup, it.latitudeDeg, it.longitudeDeg)) }
        StereoObliqueVector.all().forEach { add(Sample("estereo ${it.case}", it.setup, it.latitudeDeg, it.longitudeDeg)) }
        StereoPolarVector.all().forEach { add(Sample("polar ${it.case}", it.setup, it.latitudeDeg, it.longitudeDeg)) }
        PrimeMeridianVector.all().forEach { add(Sample("meridiano ${it.case}", it.setup, it.latitudeDeg, it.longitudeDeg)) }
        PrimeMeridianVector.all("unidades_proyeccion.csv").forEach {
            add(Sample("unidades ${it.case}", it.setup, it.latitudeDeg, it.longitudeDeg))
        }
    }

    private fun projectionOf(setup: ProjectionSetup): MapProjection = (ProjectionFactory.create(setup) as ProjectionResult.Ok).projection

    @Test
    fun `S032 RF-18 las rejillas cubren los diez metodos`() {
        samples.size shouldBeGreaterThan 1500
        samples.map { it.setup.parameters.method }.toSet().size shouldBe 10
    }

    @Test
    fun `S032 RF-18 proyectar desproyectar y volver a proyectar deja el punto a menos de 0,1 mm`() {
        val failures = samples.mapNotNull { s ->
            val projection = projectionOf(s.setup)
            val point = projection.forward(s.latitudeDeg, s.longitudeDeg)
            val back = point?.let { projection.inverse(it.eastingM, it.northingM) }
            val again = back?.let { projection.forward(it.latitudeDeg, it.longitudeDeg) }
            if (point == null || back == null || again == null) {
                "${s.case} (${s.latitudeDeg}, ${s.longitudeDeg}): sin imagen"
            } else {
                val distanceM = hypot(again.eastingM - point.eastingM, again.northingM - point.northingM)
                if (distanceM > 1e-4) "${s.case} (${s.latitudeDeg}, ${s.longitudeDeg}): ${distanceM * 1000} mm" else null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-18 desproyectar y volver a proyectar devuelve las mismas geograficas a menos de 1e-9 grados`() {
        val failures = samples.mapNotNull { s ->
            val projection = projectionOf(s.setup)
            val point = projection.forward(s.latitudeDeg, s.longitudeDeg)
            val back = point?.let { projection.inverse(it.eastingM, it.northingM) }
            if (back == null) {
                "${s.case} (${s.latitudeDeg}, ${s.longitudeDeg}): sin imagen"
            } else {
                val dLat = abs(back.latitudeDeg - s.latitudeDeg)
                val dLon = abs(back.longitudeDeg - s.longitudeDeg)
                if (dLat > 9e-10 || dLon > 9e-10) "${s.case} (${s.latitudeDeg}, ${s.longitudeDeg}): Δφ=$dLat°, Δλ=$dLon°" else null
            }
        }

        withClue(failures.joinToString("\n")) { failures.shouldBeEmpty() }
    }

    @Test
    fun `S032 RF-18 la Transversa de Mercator de UTM y Web Mercator siguen ida y vuelta a menos de 0,1 mm`() {
        listOf(36.0 to -6.0, 40.4 to -3.7, 43.5 to 2.0, 28.5 to -16.0).forEach { (latitude, longitude) ->
            val utm = Utm.forward(latitude, longitude, ellipsoid = Ellipsoid.GRS80)
            val back = Utm.inverse(utm.easting, utm.northing, utm.zone)
            val again = Utm.forward(back.latitudeDeg, back.longitudeDeg, utm.zone)
            hypot(again.easting - utm.easting, again.northing - utm.northing) shouldBe (0.0 plusOrMinus 1e-4)

            val (x, y) = WebMercator.forward(latitude, longitude)
            val webBack = WebMercator.inverse(x, y)
            val (x2, y2) = WebMercator.forward(webBack.latitudeDeg, webBack.longitudeDeg)
            hypot(x2 - x, y2 - y) shouldBe (0.0 plusOrMinus 1e-4)
        }
    }
}

package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Posición del punto del suelo en el SC del proyecto, con huso fijo (S006/RF-29, RF-30). */
class ProjectPositionCalculatorTest {

    private val calculator = ProjectPositionCalculator()
    private val pole = 1.8

    /** GGA cuyo punto del suelo tiene la altura elipsoidal [groundHeight] con un jalón de 1,8 m en el RS3. */
    private fun fix(lat: Double, lon: Double, groundHeight: Double? = 700.0, separation: Double = 51.0) = GgaFix(
        "101530.20",
        lat,
        lon,
        SolutionStatus.FIX,
        24,
        0.6,
        groundHeight?.let { it + pole + ReceiverModel.RS3.baseToReferenceM - separation },
        separation,
        1.0,
        "",
    )

    private val vectors: List<List<String>> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/geodesia/proyecto_huso_fijo.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split(',') }
    }

    @Test
    fun `S006 RF-29 Este, Norte y altitud en el huso del proyecto coinciden con pyproj a 1 mm, tambien con huso forzado`() {
        vectors.size shouldBe 309
        vectors.forEach { (lat, lon, h, zone, east, north, altitude) ->
            val crs = ProjectCrs.of(GeodeticDatum.ETRS89, zone.toInt())!!
            val position = calculator.compute(fix(lat.toDouble(), lon.toDouble(), h.toDouble()), pole, crs).shouldNotBeNull()

            position.easting.shouldNotBeNull() shouldBe (east.toDouble() plusOrMinus 0.001)
            position.northing.shouldNotBeNull() shouldBe (north.toDouble() plusOrMinus 0.001)
            if (altitude.isEmpty()) {
                position.orthometricHeight.shouldBeNull()
                position.unavailable shouldContainExactly setOf(PositionUnavailable.OUTSIDE_GEOID)
            } else {
                position.orthometricHeight.shouldNotBeNull() shouldBe (altitude.toDouble() plusOrMinus 0.001)
                position.unavailable shouldBe emptySet()
            }
        }
    }

    @Test
    fun `S006 RF-30 fuera del ambito del SC del proyecto no hay coordenadas y se dice por que`() {
        listOf(34.9 to -3.0, 44.6 to -3.0, 40.0 to -10.1, 40.0 to 5.1, 28.1 to -15.4).forEach { (lat, lon) ->
            val position = calculator.compute(fix(lat, lon), pole, ProjectCrs.ETRS89_UTM30).shouldNotBeNull()

            position.easting.shouldBeNull()
            position.northing.shouldBeNull()
            position.orthometricHeight.shouldBeNull()
            position.unavailable shouldContainExactly setOf(PositionUnavailable.OUTSIDE_CRS_AREA)
        }
    }

    @Test
    fun `S006 RF-30 sin altitud en la GGA hay Este y Norte pero no altitud`() {
        val position = calculator.compute(fix(40.4168, -3.7038, groundHeight = null), pole, ProjectCrs.ETRS89_UTM30).shouldNotBeNull()

        position.easting.shouldNotBeNull()
        position.orthometricHeight.shouldBeNull()
        position.unavailable shouldContainExactly setOf(PositionUnavailable.NO_HEIGHT)
    }

    @Test
    fun `S006 RF-29 sin solucion no hay posicion`() {
        val noFix = fix(40.4168, -3.7038).copy(status = SolutionStatus.NONE)

        calculator.compute(noFix, pole, ProjectCrs.ETRS89_UTM30).shouldBeNull()
    }

    private operator fun <T> List<T>.component6(): T = this[5]

    private operator fun <T> List<T>.component7(): T = this[6]
}

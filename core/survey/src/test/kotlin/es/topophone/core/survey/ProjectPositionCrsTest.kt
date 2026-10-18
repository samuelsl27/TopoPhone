package es.topophone.core.survey

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Geodesy
import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.Utm
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Posición del punto del suelo en todos los SC de España y alturas, con factores (S008/RF-4 a RF-9, RF-12 a RF-16). */
class ProjectPositionCrsTest {

    private val calculator = ProjectPositionCalculator()
    private val pole = 1.8

    private fun fix(lat: Double, lon: Double, groundHeight: Double? = 100.0, separation: Double = 50.0) = GgaFix(
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

    private fun rows(name: String): List<List<String>> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/geodesia/$name").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split(',') }
    }

    private fun compute(lat: Double, lon: Double, crs: ProjectCrs, heights: HeightSystem = HeightSystem.ORTHOMETRIC, h: Double? = 100.0) =
        calculator.compute(fix(lat, lon, h), pole, crs, heights).shouldNotBeNull()

    @Test
    fun `S008 RF-1 REGCAN95 UTM 27 y 28 coincide con pyproj a menos de 1 mm`() {
        val vectors = rows("utm_regcan95.csv").filter { (lat, lon) ->
            ProjectCrs.REGCAN95_UTM28.area.contains(lat.toDouble(), lon.toDouble())
        }
        vectors.size shouldBeGreaterThan 40

        val failures = vectors.mapNotNull { (lat, lon, zone, east, north) ->
            val position = compute(lat.toDouble(), lon.toDouble(), ProjectCrs.of(GeodeticDatum.REGCAN95, zone.toInt())!!)
            val dE = abs(position.easting.shouldNotBeNull() - east.toDouble())
            val dN = abs(position.northing.shouldNotBeNull() - north.toDouble())
            if (dE > 0.001 || dN > 0.001) "($lat, $lon): ΔE=${dE * 1000} mm ΔN=${dN * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-4 y RF-6 en REGCAN95 geograficas la altitud usa el geoide de Canarias y coincide con pyproj`() {
        val vectors = rows("geoide_egm08_rednap_canarias.csv").filter { it[2].isNotEmpty() }
        vectors.size shouldBeGreaterThan 50

        val failures = vectors.mapNotNull { (lat, lon, undulation) ->
            val position = compute(lat.toDouble(), lon.toDouble(), ProjectCrs.REGCAN95_GEOGRAPHIC, h = 100.0)
            val dH = abs(position.height.shouldNotBeNull() - (100.0 - undulation.toDouble()))
            when {
                position.latitudeDeg != lat.toDouble() || position.longitudeDeg != lon.toDouble() -> "($lat, $lon): geográficas cambiadas"
                dH > 0.001 -> "($lat, $lon): ΔH=${dH * 1000} mm"
                else -> null
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-7 ED50 UTM 29, 30 y 31 coincide con pyproj a menos de 1 mm y la altitud usa el geoide de la Peninsula`() {
        val vectors = rows("ed50_utm.csv").filter { it[2].isNotEmpty() }
        vectors.size shouldBeGreaterThan 150

        val failures = vectors.mapNotNull { row ->
            val (lat, lon) = row[0].toDouble() to row[1].toDouble()
            val position = compute(lat, lon, ProjectCrs.of(GeodeticDatum.ED50, row[4].toInt())!!)
            val dE = abs(position.easting.shouldNotBeNull() - row[5].toDouble())
            val dN = abs(position.northing.shouldNotBeNull() - row[6].toDouble())
            val undulation = GeoidModels.egm08RednapPeninsula.undulationM(lat, lon)
            val heightOk = undulation == null || abs(position.height.shouldNotBeNull() - (100.0 - undulation)) < 0.001
            if (dE > 0.001 || dN > 0.001 || !heightOk) "($lat, $lon) huso ${row[4]}: ΔE=${dE * 1000} mm ΔN=${dN * 1000} mm" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-8 en la Peninsula pero fuera de la rejilla ED50 no hay coordenadas y se dice por que`() {
        listOf(44.47 to -3.0, 40.0 to 4.8).forEach { (lat, lon) ->
            val position = compute(lat, lon, ProjectCrs.ED50_UTM30)

            position.easting.shouldBeNull()
            position.northing.shouldBeNull()
            position.unavailable shouldContainExactly setOf(PositionUnavailable.OUTSIDE_ED50_GRID)
        }
    }

    @Test
    fun `S008 RF-5 con altura elipsoidal se da la altura elipsoidal del punto del suelo`() {
        compute(40.4168, -3.7038, ProjectCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL, h = 712.345).height shouldBe (712.345 plusOrMinus 1e-9)
        compute(28.4636, -16.2518, ProjectCrs.REGCAN95_UTM28, HeightSystem.ELLIPSOIDAL, h = 52.1).height shouldBe (52.1 plusOrMinus 1e-9)
    }

    @Test
    fun `S008 RF-6 y RF-16 en geograficas se dan latitud y longitud, sin Este, Norte ni factores`() {
        val position = compute(40.4168, -3.7038, ProjectCrs.ETRS89_GEOGRAPHIC)

        position.latitudeDeg shouldBe 40.4168
        position.longitudeDeg shouldBe -3.7038
        position.easting.shouldBeNull()
        position.scaleFactor.shouldBeNull()
        position.convergenceRad.shouldBeNull()
        position.combinedFactor.shouldBeNull()
    }

    @Test
    fun `S008 RF-9 un punto fuera del territorio del SC no tiene coordenadas`() {
        listOf(
            Triple(28.4636, -16.2518, ProjectCrs.ETRS89_UTM30),
            Triple(28.4636, -16.2518, ProjectCrs.ED50_UTM30),
            Triple(40.4168, -3.7038, ProjectCrs.REGCAN95_UTM28),
            Triple(40.4168, -3.7038, ProjectCrs.REGCAN95_GEOGRAPHIC),
        ).forEach { (lat, lon, crs) ->
            compute(lat, lon, crs).unavailable shouldContainExactly setOf(PositionUnavailable.OUTSIDE_CRS_AREA)
        }
    }

    @Test
    fun `S008 RF-13 y RF-14 el factor de escala y la convergencia coinciden con pyproj en ETRS89 y REGCAN95`() {
        val vectors = rows("factores_tm.csv").filter { row ->
            val crs = ProjectCrs.of(if (row[3].toInt() <= 28) GeodeticDatum.REGCAN95 else GeodeticDatum.ETRS89, row[3].toInt())
            row[0] == "GRS80" && crs != null && crs.area.contains(row[1].toDouble(), row[2].toDouble())
        }
        vectors.size shouldBeGreaterThan 30

        val failures = vectors.mapNotNull { row ->
            val zone = row[3].toInt()
            val crs = ProjectCrs.of(if (zone <= 28) GeodeticDatum.REGCAN95 else GeodeticDatum.ETRS89, zone)!!
            val position = compute(row[1].toDouble(), row[2].toDouble(), crs)
            val dk = abs(position.scaleFactor.shouldNotBeNull() - row[4].toDouble())
            val dGamma = abs(Math.toDegrees(position.convergenceRad.shouldNotBeNull()) - row[5].toDouble()) * 3600
            if (dk > 1e-9 || dGamma > 0.01) "(${row.take(4)}): Δk=$dk Δγ=$dGamma″" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-13 en ED50 los factores son los del elipsoide internacional en la posicion ED50`() {
        val position = compute(40.4168, -3.7038, ProjectCrs.ED50_UTM30)
        val ed50 = es.topophone.core.geodesy.Ed50.fromEtrs89(40.4168, -3.7038)!!
        val expected = Utm.factors(ed50.first, ed50.second, 30, Ellipsoid.INTERNATIONAL_1924)

        position.scaleFactor shouldBe expected.scale
        position.convergenceRad shouldBe expected.convergenceRad
    }

    @Test
    fun `S008 RF-15 el factor combinado es el de escala por el de altura`() {
        val position = compute(40.4168, -3.7038, ProjectCrs.ETRS89_UTM30, h = 712.345)
        val radius = Geodesy.gaussMeanRadius(40.4168, Ellipsoid.GRS80)

        position.combinedFactor.shouldNotBeNull() shouldBe (position.scaleFactor!! * radius / (radius + 712.345) plusOrMinus 1e-15)
    }

    @Test
    fun `S008 RF-15 sin altura no hay factor combinado`() {
        compute(40.4168, -3.7038, ProjectCrs.ETRS89_UTM30, h = null).combinedFactor.shouldBeNull()
    }

    @Test
    fun `S008 RF-12 se dice en que huso esta la posicion aunque se calcule en el del proyecto`() {
        val position = compute(42.8782, -8.5448, ProjectCrs.ETRS89_UTM30)

        position.positionZone shouldBe 29
        position.easting.shouldNotBeNull() shouldBe (Utm.forward(42.8782, -8.5448, zone = 30).easting plusOrMinus 1e-9)
    }
}

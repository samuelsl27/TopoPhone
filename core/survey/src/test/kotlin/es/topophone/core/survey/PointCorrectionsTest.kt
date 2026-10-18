package es.topophone.core.survey

import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Corregir el jalón, volver a la medida y duplicar sin tocar la observación bruta (S011/RF-12 a RF-15, RF-21; RNF-T6). */
class PointCorrectionsTest {

    private val corrections = PointCorrections()

    private val sol = PointCoordinates(GeodeticPosition(40.41668, -3.70371, 699.423), 440_297.988, 4_474_244.001, 648.311)

    private fun measured(antenna: GeodeticPosition = GeodeticPosition(40.41668, -3.70371, 701.357), coordinates: PointCoordinates = sol) =
        Point.measured(
            id = "id-1",
            projectId = "p1",
            name = "P12",
            description = "arqueta",
            measurement = Measurement(
                antenna = antenna,
                poleHeightM = 1.8,
                quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.AVERAGED, 20, 6, fromReplay = false),
                rawNmea = "\$GNGGA,1*00\n\$GNGST,1*00",
                outOfTolerance = setOf(PrecisionFailure.VERTICAL),
                measuredFromMs = 1_000,
                measuredToMs = 21_000,
                coordinates = coordinates,
            ),
            createdAtMs = 21_000,
        )

    private val edited = PointCoordinates(GeodeticPosition(40.4167, -3.7037, 699.5), 440_300.0, 4_474_244.0, 648.4)

    @Test
    fun `S011 RF-14 el suelo se recalcula desde la antena con el jalon nuevo, como pyproj`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        val rows = File(root, "fixtures/geodesia/jalon_corregido.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split(',') }
        rows.size shouldBeGreaterThan 40

        val failures = rows.mapNotNull { c ->
            val crs = ProjectCrs.of(GeodeticDatum.valueOf(c[0]), c[1].toInt()).shouldNotBeNull()
            val point = measured(antenna = GeodeticPosition(c[2].toDouble(), c[3].toDouble(), c[4].toDouble()))
            val corrected = corrections.withPoleHeight(point, c[5].toDouble(), crs, HeightSystem.ORTHOMETRIC).shouldNotBeNull().point
            val measurement = corrected.measurement.shouldNotBeNull()
            val result = measurement.coordinates
            val expectedAltitude = c[9].takeIf { it.isNotEmpty() }?.toDouble()
            val bad = abs(result.ground.ellipsoidalHeightM!! - c[6].toDouble()) > 1e-9 ||
                abs(result.easting!! - c[7].toDouble()) > 0.001 ||
                abs(result.northing!! - c[8].toDouble()) > 0.001 ||
                (expectedAltitude == null) != (result.height == null) ||
                (expectedAltitude != null && abs(result.height!! - expectedAltitude) > 0.001) ||
                corrected.coordinates != result ||
                measurement.poleHeightM != c[5].toDouble()
            if (bad) "$c → $result" else null
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S011 RF-14 la antena, las frases NMEA y la calidad no cambian al corregir el jalon`() {
        val point = measured()

        val corrected = corrections.withPoleHeight(point, 2.0, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC).shouldNotBeNull().point

        val before = point.measurement.shouldNotBeNull()
        val after = corrected.measurement.shouldNotBeNull()
        after.antenna shouldBe before.antenna
        after.rawNmea shouldBe before.rawNmea
        after.quality shouldBe before.quality
        after.outOfTolerance shouldBe before.outOfTolerance
        after.coordinates.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (701.357 - 2.0 - 0.134 plusOrMinus 1e-9)
    }

    @Test
    fun `S011 RF-14 con las coordenadas editadas se recalcula la medida y se mantienen las editadas`() {
        val point = measured().copy(coordinates = edited, edited = true)

        val result = corrections.withPoleHeight(point, 2.0, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC).shouldNotBeNull()

        result.keptEditedCoordinates shouldBe true
        result.point.coordinates shouldBe edited
        result.point.edited shouldBe true
        result.point.measurement.shouldNotBeNull().coordinates.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe
            (701.357 - 2.134 plusOrMinus 1e-9)
    }

    @Test
    fun `S011 RF-15 un punto manual no tiene jalon y no se cambia`() {
        val manual = measured().copy(origin = PointOrigin.MANUAL, measurement = null)

        corrections.withPoleHeight(manual, 2.0, ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC).shouldBeNull()
    }

    @Test
    fun `S011 RF-12 y RF-13 volver a la medida devuelve sus coordenadas y quita la marca de editado`() {
        val typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_300.0, 4_474_244.0, 648.4, HeightKind.PROJECT)
        val point = measured().copy(coordinates = edited, typed = typed, edited = true)

        val reverted = corrections.revertToMeasurement(point).shouldNotBeNull()

        reverted.coordinates shouldBe sol
        reverted.typed.shouldBeNull()
        reverted.edited shouldBe false
        corrections.revertToMeasurement(point.copy(origin = PointOrigin.MANUAL, measurement = null)).shouldBeNull()
    }

    @Test
    fun `S011 RF-21 la copia es un punto manual con las mismas coordenadas, sin la medida y con la referencia al original`() {
        val copy = corrections.duplicate(measured(), id = "id-2", name = "P13", nowMs = 50_000)

        copy shouldBe Point(
            id = "id-2",
            projectId = "p1",
            name = "P13",
            description = "arqueta",
            origin = PointOrigin.MANUAL,
            coordinates = sol,
            measurement = null,
            typed = TypedCoordinates(EntryForm.PROJECT_CRS, 440_297.988, 4_474_244.001, 648.311, HeightKind.PROJECT),
            edited = false,
            hidden = false,
            copyOf = "P12",
            createdAtMs = 50_000,
            deletedAtMs = null,
        )
    }

    @Test
    fun `S011 RF-21 la copia de un punto manual conserva lo escrito, y la de uno oculto se ve`() {
        val typed = TypedCoordinates(EntryForm.GEOGRAPHIC, 40.41668, -3.70371, null, HeightKind.PROJECT)
        val manual = measured().copy(origin = PointOrigin.MANUAL, measurement = null, typed = typed, hidden = true)

        val copy = corrections.duplicate(manual, id = "id-3", name = "P14", nowMs = 60_000)

        copy.typed shouldBe typed
        copy.hidden shouldBe false
        copy.copyOf shouldBe "P12"
    }
}

package es.topophone.core.survey

import es.topophone.core.model.localization.ActiveLocalizationInfo
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.VerticalKind
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.localization.LocalizationApplier
import es.topophone.core.survey.localization.PointLocalization
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Un punto GNSS localizado: las coordenadas mostradas, la medida sin localizar y el jalón corregido (S039/RF-17, RF-19, RF-20). */
class PointCorrectionsLocalizationTest {

    private val corrections = PointCorrections()
    private val crs = ProjectCrs.ETRS89_UTM30
    private val heights = HeightSystem.ORTHOMETRIC
    private val antenna = GeodeticPosition(40.41668, -3.70371, 701.357)
    private val localization = ActiveLocalizationInfo(
        "loc-1",
        LocalizationParams(
            centreE = 440_000.0,
            centreN = 4_474_000.0,
            knownCentreE = 1_000.0,
            knownCentreN = 2_000.0,
            rotationRad = 0.002,
            scale = 1.00003,
            hasHorizontal = true,
            verticalKind = VerticalKind.OFFSET,
            dh = 0.25,
        ),
    )

    private fun measured(edited: Boolean = false, origin: PointOrigin = PointOrigin.MEASURED): Point {
        val ground = GeodeticPosition(antenna.latitudeDeg, antenna.longitudeDeg, antenna.ellipsoidalHeightM!! - 1.934)
        val position = ProjectPositionCalculator().fromGround(ground, crs, heights)
        val sc = PointCoordinates(ground, position.easting, position.northing, position.height)
        return Point.measured(
            id = "id-1",
            projectId = "p1",
            name = "P1",
            description = "",
            measurement = Measurement(
                antenna = antenna,
                poleHeightM = 1.8,
                quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
                rawNmea = "",
                outOfTolerance = emptySet(),
                measuredFromMs = 0,
                measuredToMs = 0,
                coordinates = sc,
            ),
            createdAtMs = 1,
        ).copy(
            origin = origin,
            edited = edited,
            typed = if (edited) TypedCoordinates(EntryForm.PROJECT_CRS, 5.0, 6.0, 7.0, HeightKind.PROJECT) else null,
            coordinates = if (edited) sc.copy(easting = 5.0, northing = 6.0, height = 7.0) else sc,
        )
    }

    @Test
    fun `S039 RF-17 localizar un punto GNSS cambia las coordenadas mostradas y deja la medida y el suelo`() {
        val point = measured()

        val localized = PointLocalization.localize(point, localization)

        val sc = point.measurement!!.coordinates
        val (e, n) = LocalizationApplier.horizontal(localization.params, sc.easting!!, sc.northing!!)
        localized.coordinates.easting shouldBe (e plusOrMinus 1e-9)
        localized.coordinates.northing shouldBe (n plusOrMinus 1e-9)
        localized.coordinates.height shouldBe (sc.height!! + 0.25 plusOrMinus 1e-9)
        localized.coordinates.ground shouldBe sc.ground
        localized.measurement shouldBe point.measurement
        localized.localizationId shouldBe "loc-1"
    }

    @Test
    fun `S039 RF-19 sin localizacion el punto queda igual y sin identificador`() {
        val point = measured()

        PointLocalization.localize(point, null) shouldBe point
        point.localizationId.shouldBeNull()
    }

    @Test
    fun `S039 RF-20 un punto editado o manual no se transforma`() {
        val edited = measured(edited = true)
        val manual = measured(origin = PointOrigin.MANUAL).copy(measurement = null)

        PointLocalization.localize(edited, localization) shouldBe edited
        PointLocalization.localize(manual, localization) shouldBe manual
        PointLocalization.appliesTo(measured()) shouldBe true
        PointLocalization.appliesTo(edited) shouldBe false
    }

    @Test
    fun `S039 RF-17 el jalon corregido recalcula la medida y la localiza con la activa`() {
        val point = PointLocalization.localize(measured(), localization)

        val corrected = corrections.withPoleHeight(point, 2.0, crs, heights, localization).shouldNotBeNull().point

        val measurement = corrected.measurement.shouldNotBeNull()
        val unlocalized = ProjectPositionCalculator().fromGround(measurement.coordinates.ground, crs, heights)
        measurement.coordinates.easting shouldBe unlocalized.easting
        val (e, _) = LocalizationApplier.horizontal(localization.params, unlocalized.easting!!, unlocalized.northing!!)
        corrected.coordinates.easting shouldBe (e plusOrMinus 1e-9)
        corrected.coordinates.height shouldBe (unlocalized.height!! + 0.25 plusOrMinus 1e-9)
        corrected.localizationId shouldBe "loc-1"
    }

    @Test
    fun `S039 RF-20 el jalon corregido de un punto editado conserva lo escrito`() {
        val edited = measured(edited = true)

        val corrected = corrections.withPoleHeight(edited, 2.0, crs, heights, localization).shouldNotBeNull()

        corrected.keptEditedCoordinates shouldBe true
        corrected.point.coordinates.easting shouldBe 5.0
        corrected.point.localizationId.shouldBeNull()
    }

    @Test
    fun `S039 RF-17 volver a la medida deja las coordenadas localizadas con la activa o sin ella`() {
        val edited = measured(edited = true)

        val withLocalization = corrections.revertToMeasurement(edited, localization).shouldNotBeNull()
        val without = corrections.revertToMeasurement(edited).shouldNotBeNull()

        withLocalization.edited shouldBe false
        withLocalization.localizationId shouldBe "loc-1"
        withLocalization.coordinates shouldBe PointLocalization.coordinates(edited.measurement!!.coordinates, localization)
        without.coordinates shouldBe edited.measurement!!.coordinates
        without.localizationId.shouldBeNull()
    }
}

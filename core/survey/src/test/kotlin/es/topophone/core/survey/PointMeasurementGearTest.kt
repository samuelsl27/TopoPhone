package es.topophone.core.survey

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GearUsed
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La medida guarda el jalón y la antena con los que se hizo y usa el desplazamiento del perfil (S046/RF-20, RF-24). */
class PointMeasurementGearTest {

    private val rs4 = AntennaCatalog.ALL.first { it.ref == "catalog:RS4" }

    private fun epoch() = Epoch(
        utcMillisOfDay = 36_930_000,
        date = null,
        gga = GgaFix("t0", 40.41680, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "\$GNGGA,0*00"),
        gst = GstAccuracy("t0", 0.008, 0.006, 0.016, "\$GNGST,0*00"),
        rmc = null,
        vtg = null,
        zda = null,
        basePosition = null,
        tilt = null,
        gsa = emptyMap(),
        gsv = emptyMap(),
        completedAtMs = 7L,
    )

    private fun result(profile: AntennaProfile, poleM: Double, gear: GearUsed?) = PointMeasurement(
        MeasurementMode.INSTANT,
        5,
        true,
        poleM,
        ProjectCrs.ETRS89_UTM30,
        HeightSystem.ORTHOMETRIC,
        1.0,
        startedAtMs = 1_000_000L,
        calculator = ProjectPositionCalculator(profile = profile),
        profile = profile,
        gear = gear,
    ).onEpoch(epoch(), 1_000_000L).filterIsInstance<MeasurementUpdate.Done>().single().result

    @Test
    fun `S046 RF-24 la medida lleva el equipo con el que se hizo`() {
        val gear = ActiveGear(rs4, SavedPole("p", "Jalón corto", PoleKind.VERTICAL, 1.2), 1.2, 1.295, null).toUsed()
        gear shouldBe GearUsed("Jalón corto", PoleKind.VERTICAL, 1.2, "Reach RS4 y RS4 Pro", 0.095, 1.295)

        val measured = result(rs4, 1.2, gear)

        measured.gear shouldBe gear
        measured.poleHeightM shouldBe 1.2
    }

    @Test
    fun `S046 RF-24 sin equipo la medida no lo lleva`() {
        result(AntennaProfile.RS3, 1.8, null).gear.shouldBeNull()
    }

    @Test
    fun `S046 RF-20 el suelo se calcula con el desplazamiento del perfil`() {
        val rs3 = result(AntennaProfile.RS3, 1.8, null)
        val other = result(rs4, 1.8, null)

        rs3.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 - 1.934 plusOrMinus 1e-9)
        other.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (650.123 + 51.234 - 1.895 plusOrMinus 1e-9)
    }

    @Test
    fun `S046 RF-24 un equipo no valido no se convierte en equipo usado`() {
        ActiveGear(AntennaProfile.RS3, null, null, null, GearProblem.NO_POLE).toUsed().shouldBeNull()
        ActiveGear(AntennaProfile.RS3, SavedPole("p", "x", PoleKind.SLANT, 1.9), null, null, GearProblem.NO_RADIUS).toUsed().shouldBeNull()
    }
}

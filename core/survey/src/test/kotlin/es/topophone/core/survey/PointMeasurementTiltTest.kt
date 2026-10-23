package es.topophone.core.survey

import es.topophone.core.geodesy.Ecef
import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.gnss.nmea.TiltAccuracyCoefficients
import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/** Medida con la inclinación del ETC (S016/RF-13, RF-14, RF-19, RF-21, RF-22, RF-27; plan A5). */
class PointMeasurementTiltTest {

    private val separation = 51.234
    private val antennaHeight = 1.8 + 0.134

    /** La punta fija sobre la que se balancea el jalón. */
    private val tip = GeodeticPosition(40.416_80, -3.703_71, 699.423)

    private fun etc(
        tiltDeg: Double? = 12.0,
        directionDeg: Double? = 210.5,
        state: TiltState = TiltState.COMPENSATING,
        notification: TiltNotification = TiltNotification.NONE,
        coefficients: TiltAccuracyCoefficients? = TiltAccuracyCoefficients(north = 1.013, east = 0.993, up = 1.021),
        raw: String = "\$GNETC,x*00",
    ) = TiltData(
        utcMillisOfDay = null,
        state = state,
        notification = notification,
        headingRad = Math.toRadians(123.4),
        tiltDirectionRad = directionDeg?.let(Math::toRadians),
        tiltRad = tiltDeg?.let(Math::toRadians),
        accuracyCoefficients = coefficients,
        rawSentence = raw,
    )

    private val alignment = etc(tiltDeg = null, directionDeg = null, state = TiltState.ALIGNMENT, coefficients = null)

    /** Una época con la antena encima de [tip] según [tilt], o en [antenna] si se da. */
    private fun epoch(
        index: Int,
        tilt: TiltData?,
        antenna: GeodeticPosition? = null,
        status: SolutionStatus = SolutionStatus.FIX,
        sigma: Double = 0.008,
        withAltitude: Boolean = true,
    ): Epoch {
        val position = antenna ?: TiltEpoch.poleVector(tilt)?.let { PoleTip.alongPole(tip, it, antennaHeight) }
            ?: GeodeticPosition(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM!! + antennaHeight)
        val altitude = if (withAltitude) position.ellipsoidalHeightM!! - separation else null
        return Epoch(
            utcMillisOfDay = 36_930_000 + index * 1_000,
            date = null,
            gga = GgaFix(
                "t$index", position.latitudeDeg, position.longitudeDeg, status, 24, 0.6, altitude, separation, 1.0, "\$GNGGA,$index*00",
            ),
            gst = GstAccuracy("t$index", sigma, sigma * 0.75, sigma * 2, "\$GNGST,$index*00"),
            rmc = null,
            vtg = null,
            zda = null,
            basePosition = null,
            tilt = tilt?.copy(rawSentence = "\$GNETC,$index*00"),
            gsa = emptyMap(),
            gsv = emptyMap(),
            completedAtMs = 7L,
        )
    }

    private fun measurement(tiltMode: TiltMode, mode: MeasurementMode = MeasurementMode.AVERAGED, seconds: Int = 3) = PointMeasurement(
        mode,
        seconds,
        fixOnly = true,
        poleHeightM = 1.8,
        crs = ProjectCrs.ETRS89_UTM30,
        heightSystem = HeightSystem.ORTHOMETRIC,
        epochIntervalS = 1.0,
        startedAtMs = 1_000_000L,
        tilt = TiltSetup(tiltMode, verified = true),
    )

    private fun PointMeasurement.feed(epoch: Epoch) = onEpoch(epoch, 1_000_000L + (epoch.utcMillisOfDay - 36_930_000))

    private fun List<MeasurementUpdate>.done() = filterIsInstance<MeasurementUpdate.Done>().single().result

    private fun distanceM(a: GeodeticPosition, b: GeodeticPosition) =
        Ecef.fromGeodetic(a.latitudeDeg, a.longitudeDeg, a.ellipsoidalHeightM!!)
            .distanceTo(Ecef.fromGeodetic(b.latitudeDeg, b.longitudeDeg, b.ellipsoidalHeightM!!))

    @Test
    fun `S016 RF-19 las epocas sin estado 30 o con aviso se descartan y se avisa una vez`() {
        val m = measurement(TiltMode.APP)
        val updates = listOf(
            epoch(0, alignment),
            epoch(1, alignment),
            epoch(2, etc()),
            epoch(3, etc(notification = TiltNotification.FAST_MOTION)),
            epoch(4, null),
            epoch(5, etc()),
            epoch(6, etc()),
        ).flatMap { m.feed(it) }

        updates.filterIsInstance<MeasurementUpdate.TiltLost>().map { it.guide } shouldContainExactly
            listOf(TiltGuide.ALIGNMENT, TiltGuide.FAST_MOTION)
        val result = updates.done()
        result.epochsUsed shouldBe 3
        result.epochsDiscarded shouldBe 4
    }

    @Test
    fun `S016 RF-19 la medida instantanea espera a la primera epoca limpia`() {
        val m = measurement(TiltMode.APP, MeasurementMode.INSTANT)

        m.feed(epoch(0, etc(notification = TiltNotification.BAD_GNSS))).filterIsInstance<MeasurementUpdate.Done>().shouldBeEmpty()
        val result = m.feed(epoch(1, etc())).done()

        result.epochsUsed shouldBe 1
        result.epochsDiscarded shouldBe 1
    }

    @Test
    fun `S016 RF-13 con el jalon balanceandose sobre la punta, el promediado vuelve a la punta`() {
        val m = measurement(TiltMode.APP, seconds = 5)
        val tilts = listOf(etc(5.0, 10.0), etc(18.0, 95.0), etc(30.0, 200.0), etc(41.0, 300.0), etc(12.0, 359.0))

        val result = tilts.mapIndexed { i, t -> epoch(i, t) }.flatMap { m.feed(it) }.done()

        distanceM(result.ground, tip) shouldBeLessThan 0.001
        result.tilt.mode shouldBe TiltMode.APP
        result.tilt.verified shouldBe true
        result.maxTiltRad.shouldNotBeNull() shouldBe (Math.toRadians(41.0) plusOrMinus 1e-12)
        val angles = result.tilt.angles.shouldNotBeNull()
        angles.tiltRad shouldBe (Math.toRadians(12.0) plusOrMinus 1e-12)
        angles.directionRad shouldBe (Math.toRadians(359.0) plusOrMinus 1e-12)
        angles.headingRad.shouldNotBeNull() shouldBe (Math.toRadians(123.4) plusOrMinus 1e-12)
        angles.receiverPoleHeightM.shouldBeNull()
        // La posición en el SC sale de la punta, no de la antena.
        result.position shouldBe ProjectPositionCalculator().fromGround(result.ground, ProjectCrs.ETRS89_UTM30)
    }

    @Test
    fun `S016 RF-21 en APP la precision suma la GST y la mayor de la inclinacion`() {
        val m = measurement(TiltMode.APP, seconds = 2)
        val big = TiltAccuracyCoefficients(north = 3.0, east = 4.0, up = 2.0)

        val result = listOf(epoch(0, etc(coefficients = big)), epoch(1, etc())).flatMap { m.feed(it) }.done()

        val gst = GstAccuracy("t", 0.008, 0.006, 0.016)
        // Planta: √(3² + 4²) = 5 mm por metro de antena; vertical: 2 mm por metro.
        result.horizontalM.shouldNotBeNull() shouldBe ((gst.horizontalM!! + 5.0 * antennaHeight / 1000) plusOrMinus 1e-12)
        result.verticalM.shouldNotBeNull() shouldBe ((0.016 + 2.0 * antennaHeight / 1000) plusOrMinus 1e-12)
        result.tilt.angles.shouldNotBeNull().horizontalAccuracyM shouldBe (5.0 * antennaHeight / 1000 plusOrMinus 1e-12)
    }

    @Test
    fun `S016 RF-14 en RECEIVER la GGA es la punta, sin restar el jalon ni sumar la inclinacion`() {
        val m = measurement(TiltMode.RECEIVER, seconds = 2)

        val result = listOf(epoch(0, etc(), antenna = tip), epoch(1, etc(25.0, 40.0), antenna = tip)).flatMap { m.feed(it) }.done()

        distanceM(result.ground, tip) shouldBeLessThan 1e-6
        distanceM(result.antenna, tip) shouldBeLessThan 1e-6
        result.horizontalM.shouldNotBeNull() shouldBe (GstAccuracy("t", 0.008, 0.006, null).horizontalM!! plusOrMinus 1e-12)
        result.verticalM.shouldNotBeNull() shouldBe (0.016 plusOrMinus 1e-12)
        val angles = result.tilt.angles.shouldNotBeNull()
        angles.receiverPoleHeightM shouldBe 1.8
        angles.maxTiltRad shouldBe (Math.toRadians(25.0) plusOrMinus 1e-12)
        result.maxTiltRad.shouldNotBeNull()
    }

    @Test
    fun `S016 RF-22 en jalon vertical la inclinacion se guarda como dato y no descarta`() {
        val m = measurement(TiltMode.VERTICAL, seconds = 2)
        val antenna = GeodeticPosition(tip.latitudeDeg, tip.longitudeDeg, tip.ellipsoidalHeightM!! + antennaHeight)

        val result = listOf(epoch(0, etc(), antenna = antenna), epoch(1, etc(), antenna = antenna)).flatMap { m.feed(it) }.done()

        result.ground.ellipsoidalHeightM.shouldNotBeNull() shouldBe (tip.ellipsoidalHeightM!! plusOrMinus 1e-6)
        result.ground.latitudeDeg shouldBe tip.latitudeDeg
        result.tilt.mode shouldBe TiltMode.VERTICAL
        result.tilt.angles.shouldNotBeNull()
        result.maxTiltRad.shouldBeNull() // la inclinación máxima no se mira (S016/RF-20)

        // Con épocas en alineación se mide igual, sin ángulos.
        val aligning = measurement(TiltMode.VERTICAL, seconds = 2)
        val updates = listOf(epoch(0, alignment, antenna = antenna), epoch(1, null, antenna = antenna)).flatMap { aligning.feed(it) }
        updates.filterIsInstance<MeasurementUpdate.TiltLost>().shouldBeEmpty()
        updates.done().tilt.angles.shouldBeNull()
    }

    @Test
    fun `S016 RF-22 las frases ETC de las epocas usadas van con el punto`() {
        val m = measurement(TiltMode.APP, seconds = 2)

        val result = listOf(epoch(0, alignment), epoch(1, etc()), epoch(2, etc())).flatMap { m.feed(it) }.done()

        result.rawNmea shouldBe listOf("\$GNGGA,1*00", "\$GNGST,1*00", "\$GNETC,1*00", "\$GNGGA,2*00", "\$GNGST,2*00", "\$GNETC,2*00")
            .joinToString("\n")
        result.rawNmea shouldContain "\$GNETC,2*00"
    }

    @Test
    fun `S016 RF-27 sin altitud la punta sale sin altura`() {
        val m = measurement(TiltMode.APP, MeasurementMode.INSTANT)

        val result = m.feed(epoch(0, etc(), withAltitude = false)).done()

        result.ground.ellipsoidalHeightM.shouldBeNull()
        result.position.unavailable shouldContainExactly setOf(PositionUnavailable.NO_HEIGHT)
    }

    @Test
    fun `S016 RF-19 sin FIX y con la inclinacion limpia se avisa la perdida de la solucion`() {
        val m = measurement(TiltMode.APP, seconds = 2)

        val updates = m.feed(epoch(0, etc(), status = SolutionStatus.FLOAT))

        updates.filterIsInstance<MeasurementUpdate.SolutionLost>().size shouldBe 1
        updates.filterIsInstance<MeasurementUpdate.TiltLost>().shouldBeEmpty()
    }
}

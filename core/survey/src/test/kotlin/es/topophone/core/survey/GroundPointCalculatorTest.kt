package es.topophone.core.survey

import es.topophone.core.geodesy.GeoidModels
import es.topophone.core.geodesy.Utm
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Punto del suelo con jalón vertical (S002/RF-29 a S002/RF-33, S002/RF-36 a S002/RF-41). */
class GroundPointCalculatorTest {

    private val calculator = GroundPointCalculator()

    private fun fix(
        lat: Double = 40.4168,
        lon: Double = -3.7038,
        status: SolutionStatus = SolutionStatus.FIX,
        altitude: Double? = 650.123,
        separation: Double? = 51.234,
    ) = GgaFix("101530.20", lat, lon, status, 24, 0.6, altitude, separation, 1.0, "\$GNGGA,...")

    @Test
    fun `S002 RF-32 S002 RF-33 la altura elipsoidal del suelo es altitud mas separacion menos jalon y 0,134 m`() {
        val point = calculator.compute(fix(), poleHeightM = 1.8).shouldNotBeNull()

        point.ellipsoidalHeightM.shouldNotBeNull() shouldBe ((650.123 + 51.234 - 1.8 - 0.134) plusOrMinus 1e-9)
        point.latitudeDeg shouldBe 40.4168
        point.longitudeDeg shouldBe -3.7038
    }

    @Test
    fun `S002 RF-32 el desplazamiento del RS3 es 0,134 m de la base a su centro de referencia`() {
        ReceiverModel.RS3.baseToReferenceM shouldBe 0.134
        VerticalPole.antennaHeightM(poleHeightM = 2.0, model = ReceiverModel.RS3) shouldBe (2.134 plusOrMinus 1e-12)
    }

    @Test
    fun `S002 RF-36 las coordenadas UTM del suelo son las del huso de su longitud`() {
        val point = calculator.compute(fix(), poleHeightM = 1.8).shouldNotBeNull()

        val expected = Utm.forward(40.4168, -3.7038)
        point.utm.shouldNotBeNull().zone shouldBe 30
        point.utm.easting shouldBe expected.easting
        point.utm.northing shouldBe expected.northing
    }

    @Test
    fun `S002 RF-38 la altitud ortometrica es la elipsoidal menos la ondulacion EGM08-REDNAP`() {
        val point = calculator.compute(fix(), poleHeightM = 1.8).shouldNotBeNull()

        val n = GeoidModels.egm08RednapPeninsula.undulationM(40.4168, -3.7038)!!
        n shouldBe (51.11168141671323 plusOrMinus 0.001) // vector de referencia de PROJ
        point.orthometricHeightM.shouldNotBeNull() shouldBe ((point.ellipsoidalHeightM!! - n) plusOrMinus 1e-9)
        point.unavailable shouldBe emptySet()
    }

    @Test
    fun `S002 RF-37 fuera de los husos 27 a 31 no hay UTM`() {
        val point = calculator.compute(fix(lat = 45.0, lon = 10.0), poleHeightM = 1.8).shouldNotBeNull()

        point.utm.shouldBeNull()
        point.unavailable shouldContain Unavailable.OUTSIDE_UTM_ZONES
    }

    @Test
    fun `S008 RF-20 en Canarias se dan las UTM REGCAN95 del huso de su longitud, 27 o 28`() {
        val tenerife = calculator.compute(fix(lat = 28.4636, lon = -16.2518), poleHeightM = 1.8).shouldNotBeNull()
        val hierro = calculator.compute(fix(lat = 27.72, lon = -18.1), poleHeightM = 1.8).shouldNotBeNull()

        tenerife.utm.shouldNotBeNull().zone shouldBe 28
        tenerife.utm!!.easting shouldBe Utm.forward(28.4636, -16.2518, zone = 28).easting
        hierro.utm.shouldNotBeNull().zone shouldBe 27
        tenerife.reference shouldBe CoordinateReference.CORRECTIONS_ASSUMED_REGCAN95
    }

    @Test
    fun `S008 RF-21 en Canarias la altitud usa el geoide EGM08-REDNAP de Canarias`() {
        val point = calculator.compute(fix(lat = 28.4636, lon = -16.2518), poleHeightM = 1.8).shouldNotBeNull()
        val undulation = GeoidModels.egm08RednapCanarias.undulationM(28.4636, -16.2518).shouldNotBeNull()

        point.orthometricHeightM.shouldNotBeNull() shouldBe (point.ellipsoidalHeightM!! - undulation plusOrMinus 1e-9)
        point.unavailable shouldBe emptySet()
    }

    @Test
    fun `S002 RF-39 en el sur de Francia hay UTM pero el geoide no cubre el punto`() {
        val point = calculator.compute(fix(lat = 44.5, lon = 1.0), poleHeightM = 1.8).shouldNotBeNull()

        point.utm.shouldNotBeNull().zone shouldBe 31
        point.unavailable shouldContainExactly setOf(Unavailable.OUTSIDE_GEOID)
    }

    @Test
    fun `S002 RF-33 sin altitud o sin separacion no hay alturas`() {
        val point = calculator.compute(fix(separation = null), poleHeightM = 1.8).shouldNotBeNull()

        point.ellipsoidalHeightM.shouldBeNull()
        point.orthometricHeightM.shouldBeNull()
        point.unavailable shouldContain Unavailable.NO_HEIGHT
        point.utm.shouldNotBeNull()
    }

    @Test
    fun `S002 RF-32 sin posicion no hay punto del suelo`() {
        calculator.compute(fix(status = SolutionStatus.NONE, lat = Double.NaN, lon = Double.NaN), poleHeightM = 1.8).shouldBeNull()
    }

    @Test
    fun `S002 RF-40 solo la solucion fija tiene precision centimetrica`() {
        SolutionStatus.entries.forEach { status ->
            val point = calculator.compute(fix(status = status), poleHeightM = 1.8)
            if (status == SolutionStatus.NONE) {
                point.shouldBeNull()
            } else {
                point.shouldNotBeNull().isCentimetric shouldBe (status == SolutionStatus.FIX)
            }
        }
    }

    @Test
    fun `S002 RF-41 las coordenadas estan en el sistema de las correcciones y se suponen ETRS89`() {
        calculator.compute(fix(), poleHeightM = 1.8).shouldNotBeNull().reference shouldBe CoordinateReference.CORRECTIONS_ASSUMED_ETRS89
    }

    @ParameterizedTest
    @ValueSource(strings = ["1.8", "1,8", " 2.000 ", "0", "10", "10.0", "0,000"])
    fun `S002 RF-29 S002 RF-31 alturas de jalon validas entre 0 y 10 m con punto o coma decimal`(text: String) {
        PoleHeight.parse(text).shouldNotBeNull()
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "  ", "-0.1", "10.001", "abc", "1.8.2", "1,8,2", "NaN", "Infinity"])
    fun `S002 RF-31 alturas de jalon no validas se rechazan`(text: String) {
        PoleHeight.parse(text).shouldBeNull()
    }

    @Test
    fun `S002 RF-29 la altura del jalon por defecto es 1,800 m`() {
        PoleHeight.DEFAULT_M shouldBe 1.8
        PoleHeight.parse("1,8") shouldBe 1.8
    }
}

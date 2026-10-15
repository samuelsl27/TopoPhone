package es.topophone.core.gnss.nmea

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

/** Frases EBP y ETC de Emlid (S003/RF-5, S003/RF-6, S003/RF-7). */
class EbpEtcParserTest {

    private fun sentence(body: String) =
        NmeaFramer().feed(("$" + body + "*" + "%02X".format(NmeaChecksum.compute(body)) + "\r\n").toByteArray()).single()

    @Test
    fun `S003 RF-5 una EBP da la posicion de la base con altura elipsoidal`() {
        val base = EbpParser.parse(sentence("GNEBP,4025.0080000,N,00342.2228000,W,701.357,M")).shouldNotBeNull()

        base.latitudeDeg shouldBe ((40 + 25.008 / 60) plusOrMinus 1e-12)
        base.longitudeDeg shouldBe (-(3 + 42.2228 / 60) plusOrMinus 1e-12)
        base.ellipsoidalHeightM shouldBe 701.357
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["GNEBP,,,,,,", "GNEBP,4025.0080000,N,00342.2228000,X,701.357,M", "GNEBP,4025.0080000,N,00342.2228000,W,70x.357,M"],
    )
    fun `S003 RF-7 una EBP sin posicion o con un campo imposible no da base`(body: String) {
        EbpParser.parse(sentence(body)).shouldBeNull()
    }

    @Test
    fun `S003 RF-6 una ETC compensando da hora, estado, aviso, rumbo, inclinacion y coeficientes`() {
        val tilt = EtcParser.parse(sentence("GNETC,101530.20,30,0,123.40,210.50,15.25,1.10,1.20,0.30")).shouldNotBeNull()

        tilt.utcMillisOfDay shouldBe 36_930_200
        tilt.state shouldBe TiltState.COMPENSATING
        tilt.notification shouldBe TiltNotification.NONE
        tilt.headingRad.shouldNotBeNull() shouldBe (Math.toRadians(123.40) plusOrMinus 1e-12)
        tilt.tiltDirectionRad.shouldNotBeNull() shouldBe (Math.toRadians(210.50) plusOrMinus 1e-12)
        tilt.tiltRad.shouldNotBeNull() shouldBe (Math.toRadians(15.25) plusOrMinus 1e-12)
        tilt.accuracyCoefficients shouldBe TiltAccuracyCoefficients(north = 1.10, east = 1.20, up = 0.30)
    }

    @Test
    fun `S003 RF-6 una ETC con la inclinacion apagada solo trae la hora`() {
        val tilt = EtcParser.parse(sentence("GNETC,101530.20,,,,,,,,")).shouldNotBeNull()

        tilt.state shouldBe TiltState.OFF
        tilt.notification shouldBe TiltNotification.OFF
        tilt.headingRad.shouldBeNull()
        tilt.accuracyCoefficients.shouldBeNull()
    }

    @ParameterizedTest
    @CsvSource("0,FATAL", "10,SETUP", "20,ALIGNMENT", "30,COMPENSATING")
    fun `S003 RF-6 cada estado de la inclinacion se reconoce`(code: String, state: TiltState) {
        EtcParser.parse(sentence("GNETC,101530.20,$code,0,123.40,,,,,")).shouldNotBeNull().state shouldBe state
    }

    @ParameterizedTest
    @CsvSource("0,NONE", "10,FAST_MOTION", "20,BAD_GNSS", "30,FILTER_FAULT")
    fun `S003 RF-6 cada aviso de la inclinacion se reconoce`(code: String, notification: TiltNotification) {
        EtcParser.parse(sentence("GNETC,101530.20,20,$code,123.40,,,,,")).shouldNotBeNull().notification shouldBe notification
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "GNETC,101530.20,25,0,123.40,,,,,", // estado inexistente
            "GNETC,101530.20,30,40,123.40,210.50,15.25,1.10,1.20,0.30", // aviso inexistente
            "GNETC,101530.20,30,0,361.00,210.50,15.25,1.10,1.20,0.30", // rumbo fuera de rango
            "GNETC,101530.20,30,0,123.40,210.50,181.00,1.10,1.20,0.30", // inclinación fuera de rango
            "GNETC,101530.20,30,0,123.40,210.50,15.25,1.x0,1.20,0.30", // coeficiente no numérico
        ],
    )
    fun `S003 RF-7 una ETC con un campo que no se puede interpretar se descarta`(body: String) {
        EtcParser.parse(sentence(body)).shouldBeNull()
    }
}

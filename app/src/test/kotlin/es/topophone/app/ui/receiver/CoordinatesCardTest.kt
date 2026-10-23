package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.format.AngleFormat
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.GroundPoint
import es.topophone.core.survey.GroundPointCalculator
import es.topophone.core.survey.Unavailable
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSnapshot
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Tarjeta Coordenadas de la pantalla Receptor (S002/RF-29 a RF-41, S008/RF-20, RF-21). Interfaz en español (es-ES). */
@RunWith(AndroidJUnit4::class)
class CoordinatesCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val spanish = Locale.forLanguageTag("es-ES")
    private val receiving = ConnectionState.Receiving("reach-RS3")

    private fun gga(status: SolutionStatus = SolutionStatus.FIX, latitudeDeg: Double = 40.41668, longitudeDeg: Double = -3.70371) = GgaFix(
        utcTime = "101530.20",
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        status = status,
        satellites = 24,
        hdop = 0.6,
        altitudeM = 650.123,
        geoidSeparationM = 51.234,
        correctionAgeS = 1.0,
        rawSentence = "",
    )

    /** Punto real del calculador con un jalón de 1,800 m: h = 650,123 + 51,234 − (1,800 + 0,134). */
    private val madrid: GroundPoint = GroundPointCalculator().compute(gga(), 1.8).shouldNotBeNull()

    private fun show(
        point: GroundPoint? = madrid,
        connection: ConnectionState = receiving,
        poleText: String = "1,800",
        poleValid: Boolean = true,
        actions: ReceiverActions = ReceiverActions(),
    ) {
        val state = ReceiverUiState(
            snapshot = ReceiverSnapshot(connection, gga = gga(point?.solution ?: SolutionStatus.FIX)),
            groundPoint = point,
            poleHeightText = poleText,
            poleHeightValid = poleValid,
        )
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(ReceiverPage.COORDINATES, ThemeState.DEFAULT, {}, state, actions, {})
            }
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun shown(id: Int) {
        compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S002 RF-29 la altura del jalon se escribe en metros desde la punta hasta la base del receptor`() {
        var pole by mutableStateOf("1,800")
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(
                    ReceiverPage.COORDINATES,
                    ThemeState.DEFAULT,
                    {},
                    ReceiverUiState(snapshot = ReceiverSnapshot(receiving), poleHeightText = pole),
                    ReceiverActions(onPoleHeightChange = { pole = it }),
                    {},
                )
            }
        }

        node(POLE_HEIGHT_TAG).assert(hasText("1,800")).assert(hasText(text(R.string.pole_height_label)))
        shown(R.string.pole_height_help)
        node(POLE_HEIGHT_TAG).performTextClearance()
        node(POLE_HEIGHT_TAG).performTextInput("2,05")
        compose.waitForIdle()

        pole shouldBe "2,05"
    }

    @Test
    fun `S002 RF-31 una altura no valida se marca e indica el rango valido`() {
        show(poleText = "12", poleValid = false)

        shown(R.string.pole_height_invalid)
        text(R.string.pole_height_invalid).contains("0") shouldBe true
        text(R.string.pole_height_invalid).contains("10 m") shouldBe true
    }

    @Test
    fun `S002 RF-34 latitud y longitud en DMS con 5 decimales de segundo y en DD con 9 decimales`() {
        show()

        node(COORD_LATITUDE_TAG)
            .assert(hasText(AngleFormat.latitudeDms(madrid.latitudeDeg, spanish)))
            .assert(hasText("40,416680000"))
        node(COORD_LONGITUDE_TAG)
            .assert(hasText(AngleFormat.longitudeDms(madrid.longitudeDeg, spanish)))
            .assert(hasText("-3,703710000"))
        AngleFormat.latitudeDms(madrid.latitudeDeg, spanish) shouldBe "40° 25′ 00,04800″ N"
    }

    @Test
    fun `S002 RF-32 S002 RF-33 S002 RF-35 la altura elipsoidal del punto del suelo con 3 decimales`() {
        show()

        node(COORD_ELLIPSOIDAL_TAG).assert(hasText("699,423 m"))
    }

    @Test
    fun `S002 RF-36 UTM en el huso de su longitud con Este y Norte en metros con 3 decimales`() {
        show()
        val utm = madrid.utm.shouldNotBeNull()

        node(COORD_UTM_ZONE_TAG).assert(hasText("30"))
        node(COORD_EASTING_TAG).assert(hasText(metres(utm.easting)))
        node(COORD_NORTHING_TAG).assert(hasText(metres(utm.northing)))
    }

    @Test
    fun `S002 RF-37 fuera de los husos 27 a 31 UTM no esta disponible`() {
        show(point = madrid.copy(utm = null, unavailable = setOf(Unavailable.OUTSIDE_UTM_ZONES)))

        node(COORD_UTM_ZONE_TAG).assert(hasText(text(R.string.unavailable_outside_utm)))
        compose.onNodeWithTag(COORD_EASTING_TAG).assertDoesNotExist()
    }

    @Test
    fun `S002 RF-38 altitud ortometrica EGM08-REDNAP con 3 decimales`() {
        show()
        val orthometric = madrid.orthometricHeightM.shouldNotBeNull()

        node(COORD_ORTHOMETRIC_TAG).assert(hasText(metres(orthometric))).assert(hasText(text(R.string.coord_orthometric_height)))
    }

    @Test
    fun `S002 RF-39 fuera del modelo de geoide la altitud no esta disponible y se dice por que`() {
        show(point = madrid.copy(orthometricHeightM = null, unavailable = setOf(Unavailable.OUTSIDE_GEOID)))

        node(COORD_ORTHOMETRIC_TAG).assert(hasText(text(R.string.unavailable_outside_geoids)))
    }

    @Test
    fun `S002 RF-33 sin altitud en la GGA las alturas no estan disponibles y se dice por que`() {
        show(point = madrid.copy(ellipsoidalHeightM = null, orthometricHeightM = null, unavailable = setOf(Unavailable.NO_HEIGHT)))

        node(COORD_ELLIPSOIDAL_TAG).assert(hasText(text(R.string.unavailable_no_height)))
        node(COORD_ORTHOMETRIC_TAG).assert(hasText(text(R.string.unavailable_no_height)))
    }

    @Test
    fun `S002 RF-40 sin solucion fija se avisa de que no hay precision centimetrica`() {
        show(point = madrid.copy(solution = SolutionStatus.FLOAT))
        shown(R.string.coordinates_not_centimetric)
    }

    @Test
    fun `S002 RF-40 con solucion fija no se muestra el aviso`() {
        show()
        compose.onNodeWithText(text(R.string.coordinates_not_centimetric)).assertDoesNotExist()
    }

    @Test
    fun `S002 RF-41 se indica que las coordenadas estan en el sistema de las correcciones, supuesto ETRS89`() {
        show()

        shown(R.string.coordinates_reference)
        text(R.string.coordinates_reference).contains("ETRS89") shouldBe true
    }

    @Test
    fun `S002 RF-32 sin recibir datos no se muestran coordenadas antiguas pero si la altura del jalon`() {
        show(connection = ConnectionState.Lost("reach-RS3"))

        shown(R.string.coordinates_waiting)
        compose.onNodeWithTag(COORD_LATITUDE_TAG).assertDoesNotExist()
        node(POLE_HEIGHT_TAG).assertIsDisplayed()
    }

    @Test
    fun `S003 RF-13 con Sin datos no se muestran las coordenadas de la ultima epoca`() {
        show(connection = ConnectionState.NoData("reach-RS3"))

        shown(R.string.coordinates_waiting)
        compose.onNodeWithTag(COORD_LATITUDE_TAG).assertDoesNotExist()
    }

    private fun metres(value: Double) = LengthFormat.metres(value, spanish)

    /** Santa Cruz de Tenerife, en el huso 28. */
    private val tenerife: GroundPoint = GroundPointCalculator().compute(
        gga(latitudeDeg = 28.4636, longitudeDeg = -16.2518),
        1.8,
    ).shouldNotBeNull()

    @Test
    fun `S008 RF-20 en Canarias se dan las UTM REGCAN95 del huso de su longitud`() {
        show(point = tenerife)
        val utm = tenerife.utm.shouldNotBeNull()

        node(COORD_UTM_ZONE_TAG).assert(hasText("28"))
        node(COORD_EASTING_TAG).assert(hasText(metres(utm.easting)))
        node(COORD_NORTHING_TAG).assert(hasText(metres(utm.northing)))
        shown(R.string.coordinates_reference_regcan95)
        compose.onNodeWithText(text(R.string.coordinates_reference)).assertDoesNotExist()
    }

    @Test
    fun `S008 RF-21 en Canarias la altitud sale del modelo EGM08-REDNAP de Canarias`() {
        show(point = tenerife)

        node(COORD_ORTHOMETRIC_TAG).assert(hasText(metres(tenerife.orthometricHeightM.shouldNotBeNull())))
    }

    @Test
    fun `S016 RF-17 sin estado 30 dice Punta sin compensar`() {
        val fix = gga()
        val state = ReceiverUiState(
            snapshot = ReceiverSnapshot(receiving, gga = fix),
            groundPoint = GroundPointCalculator().compute(fix, 1.8),
            tipUncompensated = true,
        )
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { CoordinatesCard(state) {} } }

        compose.onNodeWithTag(COORD_UNCOMPENSATED_TAG).assert(hasText(text(R.string.tilt_tip_uncompensated)))
    }
}

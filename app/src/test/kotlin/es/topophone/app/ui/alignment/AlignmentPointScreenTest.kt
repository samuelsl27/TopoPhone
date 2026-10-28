package es.topophone.app.ui.alignment

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.AxisPoint
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** La pantalla de calcular un punto sobre un eje: lado en palabras, resultado, fuera del eje y guardar (S049/RF-20 a RF-22, RNF-T8). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AlignmentPointScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val point = AxisPoint(
        chainage = 1240.5,
        offset = -3.5,
        easting = 440290.123,
        northing = 4474257.456,
        axisEasting = 440290.0,
        axisNorthing = 4474257.0,
        azimuth = Math.toRadians(60.0),
        curvature = -1.0 / 150,
    )

    private val ready = AlignmentPointUiState(
        loaded = true,
        alignmentName = "Eje B",
        startChainageM = 1000.5,
        endChainageM = 1380.5,
        hasProfile = true,
        chainageText = "1+240,5",
        offsetText = "-3,5",
        name = "7",
        reading = AxisReading(point, 101.25, -1.662),
    )

    private fun show(state: AlignmentPointUiState, actions: AlignmentPointActions = AlignmentPointActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { AlignmentPointScreen(ThemeState.DEFAULT, {}, state, actions, {}) }
        }
    }

    @Test
    fun `S049 RF-20 la pantalla dice el eje con su longitud y sus PK`() {
        show(ready)

        compose.onNodeWithText("Eje B").assertIsDisplayed()
        compose.onNodeWithText("380,0 m · del PK 1+000,50 al 1+380,50").assertIsDisplayed()
    }

    @Test
    fun `S049 RF-20 escribir el PK y el desplazamiento avisa`() {
        val state = androidx.compose.runtime.mutableStateOf(ready.copy(chainageText = "", offsetText = ""))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                AlignmentPointScreen(
                    ThemeState.DEFAULT,
                    {},
                    state.value,
                    AlignmentPointActions(
                        onChainage = { state.value = state.value.copy(chainageText = it) },
                        onOffset = { state.value = state.value.copy(offsetText = it) },
                    ),
                    {},
                )
            }
        }

        compose.onNodeWithTag(POINT_AXIS_CHAINAGE_TAG).performScrollTo().performTextInput("1+240,5")
        compose.onNodeWithTag(POINT_AXIS_OFFSET_TAG).performScrollTo().performTextInput("-3,5")

        state.value.chainageText shouldBe "1+240,5"
        state.value.offsetText shouldBe "-3,5"
    }

    @Test
    fun `S049 RNF-T8 el desplazamiento se dice con la palabra izquierda o derecha y no solo con el signo`() {
        show(ready)
        compose.onNodeWithTag(POINT_AXIS_SIDE_TAG).assertIsDisplayed()
        compose.onNodeWithText("3,500 m a la izquierda").assertExists()
    }

    @Test
    fun `S049 RNF-T8 un desplazamiento positivo es a la derecha y 0 es sobre el eje`() {
        show(ready.copy(offsetText = "2"))
        compose.onNodeWithText("2,000 m a la derecha").assertExists()
    }

    @Test
    fun `S049 RF-20 sobre el eje se dice sobre el eje`() {
        show(ready.copy(offsetText = "0"))
        compose.onNodeWithText("Sobre el eje").assertExists()
    }

    @Test
    fun `S049 RF-20 el resultado da Este Norte azimut radio cota y pendiente`() {
        show(ready)

        compose.onNodeWithTag(POINT_AXIS_RESULT_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(POINT_AXIS_EASTING_TAG).assertIsDisplayed()
        compose.onNodeWithText("Este: 440.290,123 m").assertExists()
        compose.onNodeWithText("Norte: 4.474.257,456 m").assertExists()
        compose.onNodeWithTag(POINT_AXIS_AZIMUTH_TAG).assertExists()
        compose.onNodeWithText("Radio del eje: 150,000 m").assertExists()
        compose.onNodeWithText("Cota de la rasante: 101,250 m").assertExists()
        compose.onNodeWithText("Pendiente: -1,662 %").assertExists()
        compose.onNodeWithText("PK 1+240,50, 3,500 m a la izquierda").assertExists()
    }

    @Test
    fun `S049 RF-20 en una recta el radio es recta y sin rasante no hay cota`() {
        show(ready.copy(reading = AxisReading(point.copy(curvature = 0.0), null, null)))

        compose.onNodeWithText("Radio del eje: recta").assertExists()
        compose.onNodeWithTag(POINT_AXIS_NO_PROFILE_TAG).assertExists()
        compose.onNodeWithTag(POINT_AXIS_ELEVATION_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-21 un PK fuera del eje dice el PK inicial y el final y no hay resultado`() {
        show(ready.copy(reading = null, outside = true))

        compose.onNodeWithTag(POINT_AXIS_OUTSIDE_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("El PK está fuera del eje: el PK inicial es 1+000,50 y el final 1+380,50.").assertExists()
        compose.onNodeWithTag(POINT_AXIS_RESULT_TAG).assertDoesNotExist()
        compose.onNodeWithTag(POINT_AXIS_SAVE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-20 un PK o un desplazamiento que no son numeros se dicen`() {
        show(ready.copy(reading = null, chainageInvalid = true, offsetInvalid = true))

        compose.onNodeWithText("El PK no es un número.").assertExists()
        compose.onNodeWithText("El desplazamiento no es un número.").assertExists()
    }

    @Test
    fun `S049 RF-22 Guardar punto guarda con el nombre escrito`() {
        var saved = 0
        show(ready, AlignmentPointActions(onSave = { saved++ }))

        compose.onNodeWithTag(POINT_AXIS_SAVE_TAG).performScrollTo().assertIsEnabled().performClick()

        saved shouldBe 1
    }

    @Test
    fun `S049 RF-22 sin nombre no se puede guardar y un nombre repetido se dice`() {
        show(ready.copy(name = "", duplicateName = true))

        compose.onNodeWithTag(POINT_AXIS_SAVE_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Ya hay un punto con ese nombre.", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `S049 RF-22 tras guardar se dice el nombre y se puede calcular otro punto`() {
        var another = 0
        show(ready.copy(savedName = "7"), AlignmentPointActions(onAnother = { another++ }))

        compose.onNodeWithTag(POINT_AXIS_SAVED_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Punto «7» guardado como «Calculado sobre eje».").assertExists()
        compose.onNodeWithTag(POINT_AXIS_ANOTHER_TAG).performScrollTo().performClick()

        another shouldBe 1
        compose.onNodeWithTag(POINT_AXIS_SAVE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-22 un punto fuera del ambito del sistema se dice`() {
        show(ready.copy(outsideCrsArea = true))

        compose.onNodeWithTag(POINT_AXIS_AREA_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S049 RNF-T9 en pies las coordenadas y el desplazamiento van en pies`() {
        show(
            ready.copy(
                unit = LinearUnit.INTERNATIONAL_FOOT,
                reading = AxisReading(point.copy(offset = 0.3048), null, null),
                offsetText = "1",
            ),
        )

        compose.onNodeWithText("1,000 ft a la derecha").assertExists()
        compose.onNodeWithText("Este: 1.444.", substring = true).assertExists()
    }
}

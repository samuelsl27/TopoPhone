package es.topophone.app.ui.receiver

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.survey.AntennaCatalog
import es.topophone.core.survey.GearProblem
import es.topophone.core.survey.GearProblemField
import es.topophone.core.survey.GearResolver
import es.topophone.core.survey.SavedPole
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** La pantalla "Antena y jalón" (S046/RF-1, RF-3 a RF-12, RF-15 a RF-19, RF-27; RNF-T7, RNF-T8). Interfaz en español. */
@RunWith(AndroidJUnit4::class)
class GearScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val standard = SavedPole("p1", "Jalón estándar", PoleKind.VERTICAL, 1.8)
    private val short = SavedPole("p2", "Jalón corto", PoleKind.VERTICAL, 1.2)
    private val own = es.topophone.core.survey.AntennaProfile("own:a1", "Mi antena", 0.12, 0.06, builtIn = false)

    private fun state(
        locked: Boolean = false,
        message: GearMessage? = null,
        profileRef: String? = null,
        activePole: String? = null,
        poles: List<SavedPole> = listOf(standard, short),
    ) = GearUiState(
        profiles = AntennaCatalog.ALL + own,
        poles = poles,
        gear = GearResolver.resolve(profileRef, listOf(own), activePole, poles),
        locked = locked,
        message = message,
    )

    private fun show(state: GearUiState, actions: GearActions = GearActions()) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ReceiverPageScreen(
                    ReceiverPage.GEAR,
                    ThemeState.DEFAULT,
                    {},
                    ReceiverUiState(),
                    ReceiverActions(),
                    {},
                    gear = state,
                    gearActions = actions,
                )
            }
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun shown(id: Int, vararg args: Any) {
        compose.onNodeWithText(text(id, *args)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S046 RF-1 y RF-15 la pantalla lista el catalogo con sus desplazamientos y el equipo activo con su altura de antena`() {
        show(state())

        AntennaCatalog.ALL.forEach { node(gearProfileTag(it.ref)).assert(hasText(it.name)) }
        node(gearProfileTag("catalog:RS3")).assert(hasText(text(R.string.gear_profile_detail, "0,134 m")))
        node(gearProfileTag("own:a1")).assert(hasText(text(R.string.gear_profile_detail_radius, "0,120 m", "0,060 m")))
        node(GEAR_ANTENNA_HEIGHT_TAG).assert(hasText(text(R.string.gear_antenna_height, "1,934 m")))
        node(GEAR_VERTICAL_HEIGHT_TAG).assert(hasText(text(R.string.gear_vertical_height, "1,800 m")))
        node(gearPoleTag("p2")).assert(hasText(text(R.string.gear_pole_detail_vertical, "1,200 m")))
    }

    @Test
    fun `S046 RF-13 y RF-14 elegir otro perfil o jalon llama a las acciones con dos toques como maximo`() {
        val calls = mutableListOf<String>()
        show(state(), GearActions(onSelectProfile = { calls += "profile:$it" }, onSelectPole = { calls += "pole:$it" }))

        node(gearPoleTag("p2")).performClick()
        node(gearProfileTag("catalog:RS4")).performClick()

        calls shouldBe listOf("pole:p2", "profile:catalog:RS4")
    }

    @Test
    fun `S046 RF-27 con una medida en curso las filas no se pueden elegir y lo dice`() {
        show(state(locked = true))

        node(gearPoleTag("p2")).assertIsNotEnabled()
        node(gearProfileTag("catalog:RS4")).assertIsNotEnabled()
        shown(R.string.gear_locked)
    }

    @Test
    fun `S046 RF-28 sin radio un jalon inclinado muestra el problema que impide medir`() {
        val slant = SavedPole("p3", "Inclinado", PoleKind.SLANT, 1.95)
        show(state(activePole = "p3", poles = listOf(standard, slant)))

        node(GEAR_PROBLEM_TAG).assert(hasText(text(R.string.gear_problem_no_radius)))
        state(activePole = "p3", poles = listOf(standard, slant)).gear.problem shouldBe GearProblem.NO_RADIUS
    }

    @Test
    fun `S046 RF-3 un perfil nuevo se escribe con nombre, desplazamiento y radio opcional`() {
        var saved: List<Any?>? = null
        show(
            state(),
            GearActions(onSaveProfile = { id, name, offset, radius, done ->
                saved = listOf(id, name, offset, radius)
                done(true)
            }),
        )

        node(GEAR_NEW_PROFILE_TAG).performClick()
        compose.onNodeWithTag(GEAR_DIALOG_NAME_TAG).performTextInput("Otra antena")
        compose.onNodeWithTag(GEAR_DIALOG_FIELD_TAG).performTextInput("0,15")
        compose.onNodeWithTag(GEAR_DIALOG_RADIUS_TAG).performTextInput("0,05")
        compose.onNodeWithTag(GEAR_DIALOG_SAVE_TAG).performClick()
        compose.waitForIdle()

        saved shouldBe listOf(null, "Otra antena", "0,15", "0,05")
        compose.onNodeWithTag(GEAR_DIALOG_SAVE_TAG).assertDoesNotExistCompat()
    }

    @Test
    fun `S046 RF-4 un perfil no valido muestra el motivo y deja el dialogo abierto`() {
        show(
            state(message = GearMessage.Invalid(GearTarget.PROFILE, GearProblemField.OFFSET)),
            GearActions(onSaveProfile = { _, _, _, _, done -> done(false) }),
        )

        node(GEAR_NEW_PROFILE_TAG).performClick()
        compose.onNodeWithTag(GEAR_DIALOG_SAVE_TAG).performClick()

        compose.onNodeWithText(text(R.string.gear_msg_offset)).assertIsDisplayed() // dentro del diálogo no hay desplazamiento
        compose.onNodeWithTag(GEAR_DIALOG_SAVE_TAG).assertIsEnabled()
    }

    @Test
    fun `S046 RF-5 un modelo del catalogo se copia como perfil propio con sus valores`() {
        var draftOf: String? = null
        var saved: List<Any?>? = null
        show(
            state(),
            GearActions(
                onDraftProfile = { ref, label ->
                    draftOf = "$ref/$label"
                    ProfileDraft("Reach RS4/RS4 Pro (copia)", "0.095", "")
                },
                onSaveProfile = { id, name, offset, radius, done ->
                    saved = listOf(id, name, offset, radius)
                    done(true)
                },
            ),
        )

        node(gearProfileCopyTag("catalog:RS4")).performClick()
        compose.onNodeWithTag(GEAR_DIALOG_RADIUS_TAG).performTextInput("0,07")
        compose.onNodeWithTag(GEAR_DIALOG_SAVE_TAG).performClick()
        compose.waitForIdle()

        draftOf shouldBe "catalog:RS4/copia"
        saved shouldBe listOf(null, "Reach RS4/RS4 Pro (copia)", "0.095", "0,07")
    }

    @Test
    fun `S046 RF-10 y RF-16 un jalon inclinado se guarda con su tipo y su altura`() {
        var saved: List<Any?>? = null
        show(
            state(),
            GearActions(onSavePole = { id, name, kind, height, done ->
                saved = listOf(id, name, kind, height)
                done(true)
            }),
        )

        node(GEAR_NEW_POLE_TAG).performClick()
        compose.onNodeWithTag(GEAR_DIALOG_NAME_TAG).performTextInput("Inclinado")
        compose.onNodeWithTag(GEAR_DIALOG_SLANT_TAG).performClick()
        compose.onNodeWithTag(GEAR_DIALOG_FIELD_TAG).performTextInput("1,95")
        compose.onNodeWithTag(GEAR_DIALOG_SAVE_TAG).performClick()
        compose.waitForIdle()

        saved shouldBe listOf(null, "Inclinado", PoleKind.SLANT, "1,95")
    }

    @Test
    fun `S046 RF-6 RF-11 y RF-12 los avisos de borrado se leen en pantalla`() {
        show(state(message = GearMessage.ProfileInUse(listOf("AA:BB"))))
        shown(R.string.gear_msg_profile_in_use)
    }

    @Test
    fun `S046 RF-11 no se puede borrar el ultimo jalon y el aviso se ve`() {
        show(state(message = GearMessage.LastPole))
        shown(R.string.gear_msg_last_pole)
    }

    @Test
    fun `S046 RF-19 el cambio de perfil con un jalon inclinado avisa con su nombre`() {
        show(state(message = GearMessage.ProfileChanged("Inclinado")))
        shown(R.string.gear_msg_profile_changed, "Inclinado")
    }

    @Test
    fun `S046 RF-5 editar y borrar solo aparecen en los perfiles propios y todo mide al menos 56 dp`() {
        show(state())

        node(gearProfileEditTag("own:a1")).assertIsDisplayed()
        node(gearProfileDeleteTag("own:a1")).assertIsDisplayed()
        compose.onNodeWithTag(gearProfileEditTag("catalog:RS3")).assertDoesNotExistCompat()
        compose.onNodeWithTag(gearProfileDeleteTag("catalog:RS3")).assertDoesNotExistCompat()
        listOf(
            gearProfileTag("catalog:RS3"),
            gearPoleTag("p1"),
            gearPoleEditTag("p1"),
            gearPoleDeleteTag("p1"),
            GEAR_NEW_POLE_TAG,
        ).forEach {
            val bounds = node(it).getBoundsInRoot()
            ((bounds.bottom - bounds.top) >= 56.dp) shouldBe true
        }
    }

    @Test
    fun `S046 RF-9 editar un jalon abre el dialogo con sus datos y escribir la altura la cambia`() {
        var saved: List<Any?>? = null
        show(
            state(),
            GearActions(onSavePole = { id, name, kind, height, done ->
                saved = listOf(id, name, kind, height)
                done(true)
            }),
        )

        node(gearPoleEditTag("p2")).performClick()
        compose.onNodeWithTag(GEAR_DIALOG_NAME_TAG).assert(hasText("Jalón corto"))
        compose.onNodeWithTag(GEAR_DIALOG_FIELD_TAG).performTextClearance()
        compose.onNodeWithTag(GEAR_DIALOG_FIELD_TAG).performTextInput("1,3")
        compose.onNodeWithTag(GEAR_DIALOG_SAVE_TAG).performClick()
        compose.waitForIdle()

        saved shouldBe listOf("p2", "Jalón corto", PoleKind.VERTICAL, "1,3")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()
}

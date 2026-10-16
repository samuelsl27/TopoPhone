package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.FakeNtripProfiles
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.ntrip.PublicCasters
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.ntrip.SourcetableFailure
import es.topophone.core.ntrip.SourcetableResult
import es.topophone.data.ntrip.PasswordResult
import es.topophone.receiver.session.ReceiverSession
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Formulario de perfil NTRIP y casters públicos (S007/RF-2, RF-3, RF-5, RF-11 a RF-13; S002/RF-18). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class NtripProfileFormTest {

    @get:Rule
    val compose = createComposeRule()

    private val profiles = FakeNtripProfiles()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })
    private var savedBack = 0
    private val opened = mutableListOf<String>()

    private val ign = NtripProfileDraft("ERGNSS (IGN): red", "ergnss-tr.ign.es", 2101, false, "sgarcia", "VRS3M", mountpointNeedsGga = true)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun resetMain() {
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    private fun open(profileId: String? = null): NtripProfileFormViewModel {
        val vm = NtripProfileFormViewModel(profiles, { _, _, _, _, _ -> SourcetableResult.Failed(SourcetableFailure.UNREACHABLE) }, session)
        vm.start(profileId)
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                NtripProfileFormScreen(
                    ThemeState.DEFAULT,
                    {},
                    state,
                    vm.actions(onBack = { savedBack++ }, onOpenUrl = { opened += it }),
                )
            }
        }
        return vm
    }

    private fun field(tag: String) = compose.onNodeWithTag(tag).performScrollTo()

    private fun chooseOther() = compose.onNodeWithTag(CASTER_OTHER_TAG).performClick()

    @Test
    fun `S007 RF-11 al crear un perfil se ofrece elegir un caster publico o escribir otro`() {
        open()

        compose.onNodeWithTag(casterTag("ign_network")).assert(hasText(text(R.string.caster_ign_network), substring = true))
        compose.onNodeWithTag(
            casterTag("catnet"),
        ).performScrollTo().assert(hasText(text(R.string.caster_access_free_registration), substring = true))
        compose.onNodeWithTag(CASTER_OTHER_TAG).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S002 RF-18 S007 RF-2 se escriben nombre, servidor, puerto, TLS, usuario, contrasena y punto de montaje`() {
        val vm = open()
        chooseOther()

        field(PROFILE_PORT_TAG).assert(hasText("2101"))
        field(PROFILE_NAME_TAG).performTextReplacement("Mi base")
        field(PROFILE_HOST_TAG).performTextReplacement("caster.es")
        field(PROFILE_PORT_TAG).performTextReplacement("2102")
        field(PROFILE_TLS_TAG).performClick()
        field(PROFILE_USERNAME_TAG).performTextReplacement("topo")
        field(PROFILE_PASSWORD_TAG).performTextReplacement("s3cr3t")
        field(PROFILE_MOUNTPOINT_TAG).performTextReplacement("BASE1")
        field(PROFILE_SAVE_TAG).performClick()

        val saved = profiles.stored.value.values.single()
        saved.draft shouldBe NtripProfileDraft("Mi base", "caster.es", 2102, true, "topo", "BASE1", mountpointNeedsGga = null)
        runBlocking { profiles.password(saved.id) } shouldBe PasswordResult.Ok("s3cr3t")
        vm.state.value.saved shouldBe true
        compose.waitUntil(timeoutMillis = 2_000) { savedBack == 1 } // al guardar se vuelve a Correcciones
    }

    @Test
    fun `S007 RF-3 sin nombre, sin servidor y con un puerto fuera de rango no se guarda y se dice por que`() {
        open()
        chooseOther()

        field(PROFILE_PORT_TAG).performTextReplacement("70000")
        field(PROFILE_SAVE_TAG).performClick()

        field(PROFILE_NAME_TAG).assert(hasText(text(R.string.ntrip_profile_name_empty), substring = true))
        field(PROFILE_HOST_TAG).assert(hasText(text(R.string.ntrip_profile_host_empty), substring = true))
        field(PROFILE_PORT_TAG).assert(hasText(text(R.string.ntrip_profile_port_invalid), substring = true))
        profiles.stored.value.size shouldBe 0
    }

    @Test
    fun `S007 RF-3 un nombre que ya tiene otro perfil no vale`() {
        profiles.add("ign", ign, "x")
        open()
        compose.onNodeWithTag(casterTag("ign_network")).performClick()

        field(PROFILE_SAVE_TAG).performClick()

        field(PROFILE_NAME_TAG).assert(hasText(text(R.string.ntrip_profile_name_duplicate), substring = true))
    }

    @Test
    fun `S007 RF-11 S007 RF-12 elegir un caster publico rellena nombre, servidor, puerto y TLS y muestra su alta`() {
        open()

        compose.onNodeWithTag(casterTag("catnet")).performScrollTo().performClick()

        field(PROFILE_NAME_TAG).assert(hasText(text(R.string.caster_catnet)))
        field(PROFILE_HOST_TAG).assert(hasText("catnet-ip.icgc.cat"))
        field(PROFILE_PORT_TAG).assert(hasText("2101"))
        field(
            PROFILE_TLS_TAG,
        ).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.Off))
        field(PROFILE_SIGNUP_TAG).assert(hasText("https://catnet-ip.icgc.cat/spgic/es", substring = true)).performClick()
        opened shouldBe listOf("https://catnet-ip.icgc.cat/spgic/es")
    }

    @Test
    fun `S007 RF-13 un caster de acceso libre rellena tambien su usuario y contrasena publicos`() {
        val vm = open()

        compose.onNodeWithTag(casterTag("rgapa")).performScrollTo().performClick()

        field(PROFILE_USERNAME_TAG).assert(hasText("anonimo"))
        vm.state.value.password shouldBe "anonimo"
    }

    @Test
    fun `S007 RF-11 cada caster publico tiene su nombre`() {
        PublicCasters.ALL.forEach { caster -> publicCasterName(caster.id).shouldNotBeNull() }
    }

    @Test
    fun `S007 RF-5 la contrasena guardada no se ve hasta pulsar el control para verla`() {
        profiles.add("ign", ign, "s3cr3t")
        val vm = open("ign")

        field(PROFILE_PASSWORD_TAG).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        vm.state.value.passwordVisible shouldBe false
        compose.onNodeWithContentDescription(text(R.string.ntrip_profile_show_password)).assertIsDisplayed()

        field(PROFILE_PASSWORD_TOGGLE_TAG).performClick()

        vm.state.value.passwordVisible shouldBe true
        compose.onNodeWithContentDescription(text(R.string.ntrip_profile_hide_password)).assertIsDisplayed()
        field(PROFILE_PASSWORD_TAG).assert(hasText("s3cr3t"))
    }

    @Test
    fun `S007 RF-2 al editar se ven sus datos y, sin tocar la contrasena, se conserva`() {
        profiles.add("ign", ign, "s3cr3t")
        open("ign")

        field(PROFILE_HOST_TAG).assert(hasText("ergnss-tr.ign.es"))
        compose.onNodeWithTag(casterTag("ign_network")).assertDoesNotExist() // al editar no se elige caster
        field(PROFILE_PORT_TAG).performTextReplacement("2102")
        field(PROFILE_SAVE_TAG).performClick()

        profiles.updatePasswords shouldBe listOf<String?>(null)
        profiles.stored.value.getValue("ign").draft.port shouldBe 2102
    }

    @Test
    fun `S007 RF-21 un punto de montaje escrito a mano no dice si necesita la GGA`() {
        profiles.add("ign", ign, "s3cr3t")
        open("ign")

        field(PROFILE_MOUNTPOINT_TAG).performTextReplacement("CERCANA3M")
        field(PROFILE_SAVE_TAG).performClick()

        profiles.stored.value.getValue("ign").draft.mountpointNeedsGga.shouldBeNull()
    }

    @Test
    fun `S007 RF-11 con Otro caster el formulario empieza vacio con el puerto 2101 y sin alta`() {
        open()

        chooseOther()

        field(PROFILE_HOST_TAG).assert(hasText(""))
        compose.onNodeWithTag(PROFILE_SIGNUP_TAG).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.corrections_new_profile)).assertIsDisplayed()
    }
}

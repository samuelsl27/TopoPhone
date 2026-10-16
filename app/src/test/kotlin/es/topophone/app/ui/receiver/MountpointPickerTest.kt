package es.topophone.app.ui.receiver

import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.receiver.FakeNtripProfiles
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.ntrip.NtripProfileDraft
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.core.ntrip.SourcetableFailure
import es.topophone.core.ntrip.SourcetableParser
import es.topophone.core.ntrip.SourcetableResult
import es.topophone.core.ntrip.SourcetableSource
import es.topophone.core.ntrip.SourcetableStream
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Elegir el punto de montaje de la tabla de fuentes del caster (S007/RF-14 a RF-20). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class MountpointPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val profiles = FakeNtripProfiles()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val session = ReceiverSession(sessionScope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

    private val rgapa: List<SourcetableStream> by lazy {
        SourcetableParser.parse(
            SourcetableParser.lines(File(repositoryRoot, "fixtures/ntrip/sourcetables/rgapa-2101.sourcetable").readBytes()).asSequence(),
        )
    }

    private var result: SourcetableResult = SourcetableResult.Failed(SourcetableFailure.UNREACHABLE)
    private val requests = mutableListOf<List<Any>>()
    private val source = SourcetableSource { host, port, username, password, useTls ->
        requests += listOf(host, port, username, password, useTls)
        result
    }

    private val rgapaProfile = NtripProfileDraft("RGAPA (Asturias)", "rgapa.cartografia.asturias.es", 2101, false, "anonimo", "", null)

    @Before
    fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        sessionScope.cancel()
        Dispatchers.resetMain()
    }

    /** Receptor en Oviedo. */
    private fun receiverInOviedo() {
        val toApp = PipedOutputStream()
        session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
        toApp.write(nmea("GNGGA,101530.20,4321.6840000,N,00550.9640000,W,4,24,0.6,230.000,M,52.000,M,1.0,0001").toByteArray())
        toApp.flush()
        runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving && it.gga != null } } }
    }

    private fun openForm(): NtripProfileFormViewModel {
        profiles.add("rgapa", rgapaProfile, "anonimo")
        val vm = NtripProfileFormViewModel(profiles, source, session)
        vm.start("rgapa")
        compose.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                NtripProfileFormScreen(ThemeState.DEFAULT, {}, state, vm.actions(onBack = {}, onOpenUrl = {}))
            }
        }
        return vm
    }

    private fun openPicker() = compose.onNodeWithTag(PROFILE_PICK_MOUNTPOINT_TAG).performScrollTo().performClick()

    private fun rowNames(): List<String> = compose.onNodeWithTag(MOUNTPOINT_LIST_TAG).fetchSemanticsNode().children
        .mapNotNull { node ->
            node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.removePrefix("mountpoint_")
        }

    @Test
    fun `S007 RF-14 se pide la tabla de fuentes al caster del formulario con sus credenciales`() {
        result = SourcetableResult.Ok(rgapa)
        openForm()

        openPicker()

        requests shouldBe listOf(listOf<Any>("rgapa.cartografia.asturias.es", 2101, "anonimo", "anonimo", false))
        compose.onNodeWithTag(MOUNTPOINT_PICKER_TAG).assertIsDisplayed()
    }

    @Test
    fun `S007 RF-15 cada punto de montaje muestra su identificador, formato, constelaciones, si necesita GGA y su distancia`() {
        result = SourcetableResult.Ok(rgapa.map { if (it.mountpoint == "OVIE3M") it.copy(needsGga = true) else it })
        receiverInOviedo()
        openForm()

        openPicker()

        compose.onNodeWithTag(mountpointTag("OVIE3M"))
            .assert(hasText("Oviedo", substring = true))
            .assert(hasText("RTCM 3.2", substring = true))
            .assert(hasText("GPS+GLO+GAL+BDS", substring = true))
            .assert(hasText(text(R.string.mountpoint_needs_gga), substring = true))
            .assert(hasText(" km", substring = true))
    }

    @Test
    fun `S007 RF-16 con posicion del receptor van del mas cercano al mas lejano`() {
        result = SourcetableResult.Ok(rgapa)
        receiverInOviedo()
        openForm()

        openPicker()

        rowNames().take(4) shouldBe listOf("OVIE2", "OVIE3", "OVIE3M", "SIRO2")
        compose.onNodeWithTag(mountpointTag("SIRO2")).assert(hasText(text(R.string.mountpoint_distance, "7,2"), substring = true))
    }

    @Test
    fun `S007 RF-17 sin posicion del receptor van por nombre y sin distancias`() {
        result = SourcetableResult.Ok(rgapa)
        openForm()

        openPicker()

        rowNames().take(3) shouldBe listOf("AVL12", "AVL13", "AVL13M")
        compose.onAllNodesWithText(" km", substring = true).fetchSemanticsNodes().size shouldBe 0
    }

    @Test
    fun `S007 RF-18 la busqueda deja los que contienen el texto en el nombre o el identificador`() {
        result = SourcetableResult.Ok(rgapa)
        openForm()
        openPicker()

        compose.onNodeWithTag(MOUNTPOINT_SEARCH_TAG).performTextReplacement("aviles")

        rowNames() shouldBe listOf("AVL12", "AVL13", "AVL13M")
    }

    @Test
    fun `S007 RF-19 al elegir uno se pone en el perfil y se recuerda si necesita la GGA`() {
        result = SourcetableResult.Ok(rgapa)
        openForm()
        openPicker()

        compose.onNodeWithTag(MOUNTPOINT_LIST_TAG).performScrollToNode(hasTestTag(mountpointTag("AVL13M")))
        compose.onNodeWithTag(mountpointTag("AVL13M")).performClick()
        compose.onNodeWithTag(MOUNTPOINT_PICKER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PROFILE_MOUNTPOINT_TAG).performScrollTo().assert(hasText("AVL13M"))
        compose.onNodeWithTag(PROFILE_SAVE_TAG).performScrollTo().performClick()

        profiles.stored.value.getValue("rgapa").draft.let { it.mountpoint to it.mountpointNeedsGga } shouldBe ("AVL13M" to false)
    }

    @Test
    fun `S007 RF-20 si no se puede descargar la tabla se dice el motivo y se puede escribir a mano`() {
        result = SourcetableResult.Failed(SourcetableFailure.UNAUTHORIZED)
        openForm()
        openPicker()

        compose.onNodeWithTag(MOUNTPOINT_FAILURE_TAG)
            .assert(hasText(text(R.string.mountpoint_failure, text(R.string.sourcetable_unauthorized))))
        compose.onNodeWithTag(MOUNTPOINT_MANUAL_TAG).performClick()

        compose.onNodeWithTag(MOUNTPOINT_PICKER_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PROFILE_MOUNTPOINT_TAG).performScrollTo().performTextReplacement("MIBASE")
        compose.onNodeWithTag(PROFILE_MOUNTPOINT_TAG).assert(hasText("MIBASE"))
    }

    @Test
    fun `S007 RF-20 una respuesta que no es una tabla de fuentes tambien se dice`() {
        result = SourcetableResult.Failed(SourcetableFailure.NOT_A_SOURCETABLE)
        openForm()
        openPicker()

        compose.onNodeWithText(text(R.string.mountpoint_failure, text(R.string.sourcetable_not_table))).assertIsDisplayed()
    }

    @Test
    fun `S007 RNF una tabla de 5000 puntos de montaje se muestra en menos de 2 s`() {
        result = SourcetableResult.Ok(
            (0 until 5000).map { i ->
                rgapa.first().copy(mountpoint = "M%04d".format(i), latitude = 36.0 + i % 8, longitude = -1.0 * (i % 9))
            },
        )
        openForm()

        val time = measureTime {
            openPicker()
            compose.onNodeWithTag(mountpointTag("M0000")).assertIsDisplayed()
        }

        time.inWholeMilliseconds shouldBeLessThan 2_000
    }
}

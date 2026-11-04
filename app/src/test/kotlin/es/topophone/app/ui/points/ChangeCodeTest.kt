package es.topophone.app.ui.points

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.collector.COLLECTOR_CODE_NONE_TAG
import es.topophone.app.ui.collector.CollectorCodeSource
import es.topophone.app.ui.collector.codeOptionTag
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.manual
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeAttribute
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.codes.SurveyCode
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.data.point.CodeChangeResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
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

/** Cambiar el codigo de uno o varios puntos, con el aviso de los valores que se pierden (S024/RF-13 a RF-16). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ChangeCodeTest {

    @get:Rule
    val compose = createComposeRule()

    private val arq = SurveyCode(
        "c1",
        "ARQ",
        "Arqueta",
        CodeGeometry.POINT,
        0x1E88E5,
        listOf(CodeAttribute("Material", AttributeType.TEXT, "PVC"), CodeAttribute("Diametro", AttributeType.NUMBER, "0.25")),
    )
    private val poz = SurveyCode(
        "c2",
        "POZ",
        "Pozo",
        CodeGeometry.POINT,
        0xD32F2F,
        listOf(CodeAttribute("material", AttributeType.TEXT)),
        CodeSymbol.STAR,
    )
    private val sin = SurveyCode("c3", "ARB", "Arbol", CodeGeometry.POINT, 0x689F38)

    private val source = object : CollectorCodeSource {
        override fun available(projectId: String): Flow<List<SurveyCode>> = flowOf(listOf(arq, poz, sin))

        override fun recent(projectId: String): Flow<List<String>> = flowOf(emptyList())
    }

    private val withArq =
        PointCode(
            "ARQ",
            "Arqueta",
            0x1E88E5,
            CodeSymbol.CIRCLE,
            listOf(PointAttribute("Material", AttributeType.TEXT, "Acero"), PointAttribute("Diametro", AttributeType.NUMBER, "0.5")),
        )
    private val repository = FakePointRepository(
        listOf(manual("1").copy(code = withArq), manual("2").copy(code = withArq), manual("3"), manual("4")),
        clock = { 7_000 },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var change: ChangeCode

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        change = ChangeCode(scope, source, repository)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Dispatchers.resetMain()
    }

    private fun wait(predicate: (ChangeCodeUi) -> Boolean) = runBlocking { withTimeout(5_000) { change.ui.first(predicate) } }

    private fun code(id: String) = repository.points.value.single { it.id == id }.code

    @Test
    fun `S024 RF-13 elegir otro codigo se aplica a todos los puntos seleccionados`() {
        change.start("p1", listOf("id-3", "id-4"))
        wait { it.open && it.picker.totalCodes == 3 }.picker.options.map { it.code } shouldBe listOf("ARB", "ARQ", "POZ")

        change.choose(sin)

        wait { it.result is CodeChangeResult.Changed }.result shouldBe CodeChangeResult.Changed(2)
        code("id-3")?.code shouldBe "ARB"
        code("id-4")?.code shouldBe "ARB"
        code("id-1")?.code shouldBe "ARQ"
    }

    @Test
    fun `S024 RF-13 se puede quitar el codigo con Sin codigo`() {
        change.start("p1", listOf("id-3"))
        wait { it.open }

        change.choose(null)

        wait { it.result != null }
        code("id-3").shouldBeNull()
    }

    @Test
    fun `S024 RF-14 los atributos con el mismo nombre y tipo se conservan y el cambio queda en el historial`() {
        change.start("p1", listOf("id-1"))
        wait { it.open }

        change.choose(poz)
        wait { it.warning != null }
        change.confirm()

        wait { it.result != null }
        code("id-1")?.attributes shouldBe listOf(PointAttribute("material", AttributeType.TEXT, "Acero"))
        repository.historyEntries.value.single().change shouldBe PointChange.CODE
        repository.historyEntries.value.single().previous.code shouldBe withArq
    }

    @Test
    fun `S024 RF-15 si se pierde algun valor se avisa de cuales y no se aplica nada hasta confirmar`() {
        change.start("p1", listOf("id-1", "id-2", "id-3"))
        wait { it.open }

        change.choose(poz)

        val warning = wait { it.warning != null }.warning
        warning shouldBe listOf(DiscardedValue("1", "Diametro"), DiscardedValue("2", "Diametro"))
        code("id-1")?.code shouldBe "ARQ"
        code("id-3").shouldBeNull()
    }

    @Test
    fun `S024 RF-15 cancelar el aviso no cambia ningun punto`() {
        change.start("p1", listOf("id-1"))
        wait { it.open }
        change.choose(poz)
        wait { it.warning != null }

        change.cancel()

        wait { !it.open && it.warning == null }
        code("id-1") shouldBe withArq
        repository.historyEntries.value.size shouldBe 0
    }

    @Test
    fun `S024 RF-15 quitar el codigo de un punto con valores tambien avisa`() {
        change.start("p1", listOf("id-1"))
        wait { it.open }

        change.choose(null)

        wait { it.warning != null }.warning?.map { it.attribute } shouldBe listOf("Material", "Diametro")
    }

    @Test
    fun `S024 RF-16 un punto que ya no existe no cambia a ninguno`() {
        change.start("p1", listOf("id-3", "id-fantasma"))
        wait { it.open }

        change.choose(sin)

        wait { it.result != null }.result shouldBe CodeChangeResult.NotFound
        code("id-3").shouldBeNull()
    }

    private fun show(ui: ChangeCodeUi, calls: MutableList<String>) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ChangeCodeDialogs(
                    ui,
                    ChangeCodeActions(
                        onChoose = { calls += "choose:${it?.code}" },
                        onConfirm = { calls += "confirm" },
                        onCancel = { calls += "cancel" },
                    ),
                )
            }
        }
    }

    @Test
    fun `S024 RF-13 el selector muestra Sin codigo y los codigos del proyecto`() {
        val calls = mutableListOf<String>()
        show(
            ChangeCodeUi(open = true, picker = es.topophone.app.ui.collector.CollectorCodeUi(options = listOf(arq, poz), totalCodes = 2)),
            calls,
        )

        compose.onNodeWithTag(COLLECTOR_CODE_NONE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(codeOptionTag("POZ")).performClick()

        calls shouldBe listOf("choose:POZ")
    }

    @Test
    fun `S024 RF-15 el aviso nombra los atributos afectados y se confirma o se cancela`() {
        val calls = mutableListOf<String>()
        show(ChangeCodeUi(target = poz, warning = listOf(DiscardedValue("1", "Diametro"), DiscardedValue("2", "Diametro"))), calls)

        compose.onNodeWithText(
            "Al cambiar a «POZ» se perderán estos valores: 1 · Diametro; 2 · Diametro. ¿Cambiar el código?",
        ).assertIsDisplayed()
        compose.onNodeWithText("Cambiar código").performClick()

        calls shouldBe listOf("confirm")
    }
}

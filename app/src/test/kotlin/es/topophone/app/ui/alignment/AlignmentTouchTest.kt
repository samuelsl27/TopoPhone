package es.topophone.app.ui.alignment

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.MapTap
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.CadPlacement
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tocar un eje en el mapa: el eje más cercano, el PK del punto tocado, la cota de su rasante y su ficha (S049/RF-18, RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class AlignmentTouchTest {

    @get:Rule
    val compose = createComposeRule()

    private val crs = ProjectCrs.ETRS89_UTM30

    private fun axis(id: String, n0: Double, visible: Boolean = true, profile: VerticalProfile? = null) = Alignment(
        id = id,
        projectId = "p",
        name = "Eje $id",
        fileName = null,
        importedAtMs = 0L,
        source = AlignmentSource.MANUAL,
        elements = listOf(AlignmentElement.line(1000.5, 1000.0, 440000.0, n0, PI / 2)),
        profile = profile,
        visible = visible,
        extent = AlignmentExtent(440000.0, 441000.0, n0, n0),
    )

    private val profile = VerticalProfile(listOf(VerticalVertex(1000.5, 100.0), VerticalVertex(2000.5, 110.0)))

    @Test
    fun `S049 RF-18 tocar junto al eje da su PK y la cota de la rasante en ese PK`() {
        val touch = AlignmentHitTest.nearest(
            listOf(axis("a", 4474000.0, profile = profile)),
            "a",
            440250.0,
            4474002.0,
            24.0,
        ).shouldNotBeNull()

        touch.alignment.id shouldBe "a"
        touch.chainage shouldBe (1250.5 plusOrMinus 1e-6)
        touch.elevationM.shouldNotBeNull() shouldBe (102.5 plusOrMinus 1e-6)
        touch.active shouldBe true
    }

    @Test
    fun `S049 RF-18 un eje sin rasante no da cota`() {
        AlignmentHitTest.nearest(listOf(axis("a", 4474000.0)), null, 440250.0, 4474002.0, 24.0).shouldNotBeNull().elevationM.shouldBeNull()
    }

    @Test
    fun `S049 RF-18 mas lejos que un dedo o antes del inicio no se toca ningun eje`() {
        val list = listOf(axis("a", 4474000.0))

        AlignmentHitTest.nearest(list, null, 440250.0, 4474030.0, 24.0).shouldBeNull()
        AlignmentHitTest.nearest(list, null, 439990.0, 4474000.0, 24.0).shouldBeNull()
    }

    @Test
    fun `S049 RF-18 entre dos ejes se toca el mas cercano`() {
        val touch = AlignmentHitTest.nearest(listOf(axis("a", 4474000.0), axis("b", 4474010.0)), null, 440250.0, 4474007.0, 24.0)

        touch.shouldNotBeNull().alignment.id shouldBe "b"
        touch.active shouldBe false
    }

    @Test
    fun `S049 RF-19 un eje oculto no se toca`() {
        AlignmentHitTest.nearest(listOf(axis("a", 4474000.0, visible = false)), null, 440250.0, 4474001.0, 24.0).shouldBeNull()
    }

    @Test
    fun `S049 RF-18 el modelo del mapa convierte el toque en un eje tocado y lo cierra`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val projects = RoomProjectRepository(db)
            val repository = RoomAlignmentRepository(db)
            val projectId = runBlocking {
                projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
            }
            runBlocking {
                repository.add(
                    projectId,
                    listOf(
                        AlignmentToSave(
                            "Eje A",
                            null,
                            AlignmentSource.MANUAL,
                            listOf(AlignmentElement.line(0.0, 500.0, 440000.0, 4474000.0, PI / 2)),
                            null,
                            AlignmentExtent(440000.0, 440500.0, 4474000.0, 4474000.0),
                            false,
                        ),
                    ),
                    1L,
                )
            }
            val vm = AlignmentMapViewModel(repository, projects, es.topophone.data.section.RoomSectionRepository(db), dispatcher)
            vm.start(projectId)
            val (lat, lon) = CadPlacement.geographic(crs, 440200.0, 4474003.0)!!

            vm.onTap(MapTap(lat, lon, 10.0))

            val touch = runBlocking { withTimeout(10_000) { vm.touched.first { it != null } } }.shouldNotBeNull()
            touch.alignment.name shouldBe "Eje A"
            touch.chainage shouldBe (200.0 plusOrMinus 1e-3)
            touch.active shouldBe true
            val (farLat, farLon) = CadPlacement.geographic(crs, 440200.0, 4474300.0)!!
            vm.onTap(MapTap(farLat, farLon, 10.0))
            runBlocking { withTimeout(10_000) { vm.touched.first { it == null } } }
            vm.onTap(MapTap(lat, lon, 10.0))
            runBlocking { withTimeout(10_000) { vm.touched.first { it != null } } }
            vm.dismissTouch()
            vm.touched.value.shouldBeNull()
        } finally {
            Dispatchers.resetMain()
            db.close()
        }
    }

    private fun showSheet(
        touch: AlignmentTouch,
        unit: LinearUnit = LinearUnit.METRE,
        onActivate: () -> Unit = {
        },
        onCalculate: (() -> Unit)? = null,
        onStake: (() -> Unit)? = null,
        onAnalyze: (() -> Unit)? = null,
        onSections: (() -> Unit)? = null,
        sectionLines: Boolean = false,
        onSectionLines: ((Boolean) -> Unit)? = null,
        onSlopes: (() -> Unit)? = null,
        onStakeSlopes: (() -> Unit)? = null,
    ) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                AlignmentTouchSheet(
                    touch,
                    unit,
                    if (unit ==
                        LinearUnit.METRE
                    ) {
                        "m"
                    } else {
                        "ft"
                    },
                    onActivate,
                    {},
                    onCalculate,
                    onStake,
                    onAnalyze,
                    onSections,
                    sectionLines,
                    onSectionLines,
                    onSlopes,
                    onStakeSlopes,
                )
            }
        }
    }

    @Test
    fun `S049 RF-18 la ficha dice el nombre la longitud los PK y la cota`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0, profile = profile), 1250.5, 102.5, active = false))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_SHEET_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_TOUCH_NAME_TAG).assertIsDisplayed()
        compose.onNodeWithText("Longitud: 1.000,0 m").assertExists()
        compose.onNodeWithText("PK inicial 1+000,50 · PK final 2+000,50").assertExists()
        compose.onNodeWithText("PK del punto tocado: 1+250,50").assertExists()
        compose.onNodeWithText("Cota de la rasante en ese PK: 102,500 m").assertExists()
    }

    @Test
    fun `S049 RF-18 sin rasante la ficha lo dice y en pies usa la unidad del proyecto`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = false), unit = LinearUnit.INTERNATIONAL_FOOT)

        compose.onNodeWithTag(ALIGNMENT_TOUCH_NO_PROFILE_TAG).assertIsDisplayed()
        compose.onNodeWithText("Longitud: 3.280,8 ft").assertExists()
        compose.onNodeWithText("PK del punto tocado: 41+02,69").assertExists()
    }

    @Test
    fun `S049 RF-18 la ficha ofrece activar el eje o dice que ya lo esta`() {
        var activated = 0
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = false), onActivate = { activated++ })

        compose.onNodeWithTag(ALIGNMENT_TOUCH_ACTIVATE_TAG).performClick()

        activated shouldBe 1
        compose.onNodeWithTag(ALIGNMENT_TOUCH_ACTIVE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-18 un eje ya activo no ofrece activarlo`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_ACTIVE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(ALIGNMENT_TOUCH_ACTIVATE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S049 RF-20 la ficha ofrece calcular un punto sobre el eje si se le da la accion`() {
        var calculate = 0
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true), onCalculate = { calculate++ })

        compose.onNodeWithTag(ALIGNMENT_TOUCH_POINT_TAG).performClick()

        calculate shouldBe 1
    }

    @Test
    fun `S054 RF-1 la ficha ofrece Replantear eje si se le da la accion y no lo ofrece si no`() {
        var stake = 0
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true), onStake = { stake++ })

        compose.onNodeWithTag(ALIGNMENT_TOUCH_STAKE_TAG).performClick()

        stake shouldBe 1
    }

    @Test
    fun `S054 RF-1 sin la accion la ficha no muestra Replantear eje`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_STAKE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S054 RF-12 la ficha ofrece Analizar si se le da la accion y no lo ofrece si no`() {
        var analyse = 0
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true), onAnalyze = { analyse++ })

        compose.onNodeWithTag(ALIGNMENT_TOUCH_ANALYZE_TAG).performClick()

        analyse shouldBe 1
    }

    @Test
    fun `S054 RF-12 sin la accion la ficha no muestra Analizar`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_ANALYZE_TAG).assertDoesNotExist()
    }

    @Test
    fun `S050 RF-1 la ficha ofrece Secciones si se le da la accion y no lo ofrece si no`() {
        var sections = 0
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true), onSections = { sections++ })

        compose.onNodeWithTag(ALIGNMENT_TOUCH_SECTIONS_TAG).performClick()

        sections shouldBe 1
    }

    @Test
    fun `S050 RF-1 sin la accion la ficha no muestra Secciones`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_SECTIONS_TAG).assertDoesNotExist()
    }

    @Test
    fun `S050 RF-14 la ficha ofrece el interruptor de las lineas de seccion con su estado`() {
        val calls = mutableListOf<Boolean>()
        showSheet(
            AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true),
            sectionLines = false,
            onSectionLines = { calls += it },
        )

        compose.onNodeWithTag(ALIGNMENT_TOUCH_SECTION_LINES_TAG).performClick()

        calls shouldBe listOf(true)
    }

    @Test
    fun `S050 RF-14 sin la accion la ficha no ofrece las lineas de seccion`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_SECTION_LINES_TAG).assertDoesNotExist()
    }

    @Test
    fun `S051 RF-1 la ficha ofrece Taludes si se le da la accion y no lo ofrece si no`() {
        var slopes = 0
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true), onSlopes = { slopes++ })
        compose.onNodeWithTag(ALIGNMENT_TOUCH_SLOPES_TAG).performClick()
        slopes shouldBe 1
    }

    @Test
    fun `S051 RF-1 sin la accion la ficha no muestra Taludes`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_SLOPES_TAG).assertDoesNotExist()
    }

    @Test
    fun `S051 RF-9 la ficha ofrece Replantear taludes si se le da la accion y no lo ofrece si no`() {
        var stake = 0
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true), onStakeSlopes = { stake++ })
        compose.onNodeWithTag(ALIGNMENT_TOUCH_STAKE_SLOPES_TAG).performClick()
        stake shouldBe 1
    }

    @Test
    fun `S051 RF-9 sin la accion la ficha no muestra Replantear taludes`() {
        showSheet(AlignmentTouch(axis("a", 4474000.0), 1250.5, null, active = true))

        compose.onNodeWithTag(ALIGNMENT_TOUCH_STAKE_SLOPES_TAG).assertDoesNotExist()
    }
}

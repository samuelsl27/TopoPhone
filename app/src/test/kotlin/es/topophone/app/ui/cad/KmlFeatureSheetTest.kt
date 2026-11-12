package es.topophone.app.ui.cad

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.MapCamera
import es.topophone.app.ui.map.MapTap
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgAttributesCodec
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadChunkWrite
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** La ficha de un objeto de un KML con sus atributos del datos extendidos, al tocarlo en el mapa, y borrar su dibujo (S030/RF-21, RF-23, RF-27). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class KmlFeatureSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val dispatcher = UnconfinedTestDispatcher()
    private val logged = mutableListOf<String>()
    private val log = object : AppLog {
        override fun record(level: LogLevel, category: LogCategory, message: String) {
            logged += "$level $category $message"
        }
    }
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Parcelas", crs = ProjectCrs.ETRS89_UTM30))
            .shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val columns = listOf("nombre", "cota", "codigo", "fecha", "activo")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun saveDrawing(id: String) = runBlocking {
        cad.begin(
            CadDrawing(
                id, projectId, "parcelas.zip", 1_000L, CadSourceCrs.Project, LinearUnit.METRE, true, false, origin = CadOrigin.KML,
            ),
        )
        cad.addLayer(
            DesignLayer("l-$id", id, "vertices", DesignLayerKind.POINTS, CadColor.rgb(0), sourceTable = "vertices", nameField = "nombre"),
        )
        val entity =
            CadEntity(CadEntityKind.POINT, doubleArrayOf(440_000.0), doubleArrayOf(4_474_000.0), null, null, name = "Esquina norte")
        val row = GpkgAttributesCodec.encodeRow(
            listOf(
                GpkgValue.Text("Esquina norte"),
                GpkgValue.Real(646.377),
                GpkgValue.Integer(1),
                GpkgValue.Date("2026-10-01"),
                GpkgValue.Bool(true),
            ),
        )
        cad.addChunks(
            listOf(
                CadChunkWrite(
                    "l-$id",
                    entity.bounds,
                    1,
                    CadChunkCodec.encode(listOf(entity)),
                    GpkgAttributesCodec.encodeBlock(columns, listOf(row)),
                ),
            ),
        )
        cad.finish(id, mapOf("l-$id" to 1), false, false, CadExtent(440_000.0, 440_000.0, 4_474_000.0, 4_474_000.0))
    }

    private fun viewModel(): DesignMapViewModel = DesignMapViewModel(cad, projects, dispatcher, log).also {
        it.start(projectId)
        it.onCamera(MapCamera(40.4166, -3.7040, 18.0))
        runBlocking { withTimeout(5_000) { it.drawings.first { drawings -> drawings.isNotEmpty() } } }
    }

    private fun DesignMapViewModel.tapAtPoint(): DesignSelection? {
        val ground = Utm.inverse(440_000.0, 4_474_000.0, 30)
        onTap(MapTap(ground.latitudeDeg, ground.longitudeDeg, toleranceM = 60.0))
        return runBlocking { withTimeout(5_000) { selected.first { it != null } } }
    }

    private fun count(table: String) = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S031 RF-11 al tocar un objeto de un KML la ficha trae todos sus atributos`() {
        saveDrawing("s1")

        val selection = viewModel().tapAtPoint().shouldNotBeNull()

        selection.attributes shouldBe linkedMapOf(
            "nombre" to GpkgValue.Text("Esquina norte"),
            "cota" to GpkgValue.Real(646.377),
            "codigo" to GpkgValue.Integer(1),
            "fecha" to GpkgValue.Date("2026-10-01"),
            "activo" to GpkgValue.Bool(true),
        )
        selection.entity.name shouldBe "Esquina norte"
        selection.drawingName shouldBe "parcelas.zip"
        selection.layerName shouldBe "vertices"
    }

    @Test
    fun `S031 RF-11 la ficha lista los atributos con su columna y el nombre de la capa`() {
        saveDrawing("s1")
        val selection = viewModel().tapAtPoint().shouldNotBeNull()

        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                DesignElementSheet(selection, LinearUnit.METRE, "m", geographic = false, onDismiss = {})
            }
        }

        compose.onNodeWithTag(DESIGN_NAME_TAG).assertIsDisplayed()
        compose.onNodeWithText("Nombre: Esquina norte").assertIsDisplayed()
        compose.onNodeWithTag(DESIGN_ATTRIBUTES_TAG).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("cota: 646,377").assertIsDisplayed()
        compose.onNodeWithText("fecha: 2026-10-01").assertIsDisplayed()
        compose.onNodeWithText("activo: Sí").assertIsDisplayed()
    }

    @Test
    fun `S031 RF-16 la ref del objeto tocado apunta a su capa y su elemento`() {
        saveDrawing("s1")

        viewModel().tapAtPoint().shouldNotBeNull().ref shouldBe
            CadEntityRef("l-s1", runBlocking { cad.chunksIn(listOf("l-s1"), CadExtent(-1e9, 1e9, -1e9, 1e9)) }.single().chunkId, 0)
    }

    @Test
    fun `S031 RF-19 borrar el dibujo se anota sin nombres ni coordenadas y no toca los puntos`() {
        saveDrawing("s1")
        val points = count("points")
        val viewModel = viewModel()

        viewModel.deleteDrawing("s1")

        runBlocking { withTimeout(5_000) { viewModel.drawings.first { it.isEmpty() } } }
        val line = logged.single { it.contains("borrado") }
        line shouldBe "INFO APP Dibujo borrado (kml)"
        for (secret in listOf("parcelas.zip", "vertices", "Esquina", "440")) line shouldNotContain secret
        count("points") shouldBe points
        count("cad_chunk_attributes") shouldBe 0
        count("cad_chunks") shouldBe 0
    }
}

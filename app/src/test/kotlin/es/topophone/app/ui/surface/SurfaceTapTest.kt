package es.topophone.app.ui.surface

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.core.model.surface.SurfaceTileCodec
import es.topophone.core.survey.TinTiler
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RasterProjectContext
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
import es.topophone.data.surface.SurfaceProbe
import es.topophone.data.surface.SurfaceSummary
import es.topophone.data.surface.SurfaceTileWrite
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Tocar las superficies visibles en el mapa: una, dos solapadas, un hueco y fuera de todas (S061/RF-16 a RF-18). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class SurfaceTapTest {

    @get:Rule
    val compose = createComposeRule()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomSurfaceRepository(db)
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val context = RasterProjectContext(ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC)
    private val resolver = SurfaceTapResolver(SurfaceProbe(repository))

    @After
    fun close() = db.close()

    private val e0 = 440_000.0
    private val n0 = 4_474_000.0

    /** Un TIN plano de 10 × 10 celdas de 2 m a cota [z] desde ([de], 0), sin la celda (5, 5). */
    private fun writeFlat(id: String, de: Double, z: Double) = runBlocking {
        val w = 11
        val e = DoubleArray(w * w) { e0 + de + (it % w) * 2.0 }
        val n = DoubleArray(w * w) { n0 + (it / w) * 2.0 }
        val h = DoubleArray(w * w) { z }
        val t = buildList {
            for (j in 0 until 10) {
                for (i in 0 until 10) {
                    if (i == 5 && j == 5) continue
                    val a = j * w + i
                    addAll(listOf(a, a + 1, a + w + 1, a, a + w + 1, a + w))
                }
            }
        }.toIntArray()
        repository.begin(SurfaceDraft(id, project, id, "$id.xml", 1L, CadSourceCrs.Project, LinearUnit.METRE))
        val cells = TinTiler.tile(e, n, h, t)
        repository.addTiles(id, cells.map { SurfaceTileWrite(it.region, it.tile.triangleCount, SurfaceTileCodec.encode(it.tile)) })
        repository.finish(id, SurfaceSummary(SurfaceExtent(e0 + de, e0 + de + 20.0, n0, n0 + 20.0), z, z, w * w, t.size / 3, 0))
    }

    private suspend fun resolve(e: Double, n: Double) =
        resolver.resolve(repository.surfaces(project).first(), repository.activeSurfaceId(project).first(), e0 + e, n0 + n, context)

    @Test
    fun `S061 RF-16 una superficie da su nombre su tipo si es la activa y su cota`() = runBlocking<Unit> {
        writeFlat("terreno", 0.0, 612.5)

        val row = resolve(3.3, 7.1).shouldNotBeNull().rows.single()

        row.name shouldBe "terreno"
        row.kind shouldBe SurfaceKind.TIN
        row.active shouldBe true
        row.heightM!! shouldBe (612.5 plusOrMinus 1e-9)
    }

    @Test
    fun `S061 RF-17 dos superficies solapadas dan una fila cada una`() = runBlocking<Unit> {
        writeFlat("terreno", 0.0, 612.5)
        writeFlat("rasante", 10.0, 611.0)

        val rows = resolve(15.0, 3.0).shouldNotBeNull().rows

        rows.map { it.name to it.heightM } shouldBe listOf("rasante" to 611.0, "terreno" to 612.5)
    }

    @Test
    fun `S061 RF-18 en un hueco sale sin cota y fuera de todas no hay tarjeta`() = runBlocking<Unit> {
        writeFlat("terreno", 0.0, 612.5)

        resolve(11.0, 11.0).shouldNotBeNull().rows.single().heightM.shouldBeNull() // la celda (5, 5)
        resolve(50.0, 50.0).shouldBeNull()
    }

    @Test
    fun `S061 RF-16 una superficie oculta no sale al tocar`() = runBlocking<Unit> {
        writeFlat("terreno", 0.0, 612.5)
        repository.setVisible("terreno", false)

        resolve(3.3, 7.1).shouldBeNull()
    }

    @Test
    fun `S061 RF-16 RF-18 la tarjeta da la cota en las unidades del proyecto o fuera de la superficie`() {
        val ui = SurfaceTapUi(
            listOf(
                SurfaceTapRow("a", "terreno", SurfaceKind.TIN, active = true, heightM = 612.5),
                SurfaceTapRow("b", "mdt", SurfaceKind.RASTER, active = false, heightM = null),
            ),
        )
        compose.setContent { TopoPhoneTheme(EffectiveTheme.LIGHT) { SurfaceTapSheet(ui, LinearUnit.METRE, onDismiss = {}) } }

        compose.onNodeWithText("Cota de la superficie: 612,500 m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Fuera de la superficie").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Activa").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ráster").performScrollTo().assertIsDisplayed()
    }
}

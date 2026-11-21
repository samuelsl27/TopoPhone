package es.topophone.data.cad

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.GeoBox
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.surface.RoomSurfaceRepository
import es.topophone.data.surface.SurfaceDraft
import es.topophone.data.surface.SurfaceSummary
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El SC de origen de un dibujo y de una superficie, con los cuatro tipos, se guarda y se lee igual (S057/RF-2, RF-27). */
@RunWith(AndroidJUnit4::class)
class CadSourceCrsPersistenceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val cad = RoomCadRepository(db)
    private val surfaces = RoomSurfaceRepository(db)

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val library = CadSourceCrs.Library(
        CrsDefinition(
            epsg = 2154,
            name = "RGF93 v1 / Lambert-93",
            projected = true,
            datumCode = 4171,
            datumName = "RGF93 v1",
            ellipsoidSemiMajorM = 6378137.0,
            ellipsoidInverseFlattening = 298.257222101,
            primeMeridianDeg = 0.0,
            method = "lcc2",
            parameters = mapOf("latitudeOfFalseOriginDeg" to 46.5, "eastingAtFalseOrigin" to 700000.0),
            unitMetres = 1.0,
            area = GeoBox(41.15, -9.86, 51.56, 10.38),
            frame = "ETRS89",
            transformationName = "RGF93 v1 to ETRS89 (1)",
            transformationAccuracyM = 0.1,
            transformationArea = null,
            transformationSteps = "null",
        ),
    )

    private val sources = listOf(
        CadSourceCrs.Project,
        CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
        CadSourceCrs.Geographic(GeodeticDatum.ED50),
        library,
    )

    @Test
    fun `S057 RF-27 un dibujo guarda y lee los cuatro tipos de SC de origen`() = runBlocking<Unit> {
        sources.forEachIndexed { i, source ->
            cad.begin(CadDrawing("d$i", project, "plano$i.dxf", 1_000L + i, source, LinearUnit.METRE, true, false))
            cad.finish("d$i", emptyMap(), false, false, CadExtent(0.0, 1.0, 0.0, 1.0))
        }

        cad.drawings(project).first().associate { it.drawing.id to it.drawing.source } shouldBe
            sources.mapIndexed { i, source -> "d$i" to source }.toMap()
    }

    @Test
    fun `S057 RF-27 una superficie guarda y lee los cuatro tipos de SC de origen`() = runBlocking<Unit> {
        val summary = SurfaceSummary(SurfaceExtent(0.0, 1.0, 0.0, 1.0), 0.0, 1.0, 0, 0, 0)
        sources.forEachIndexed { i, source ->
            surfaces.begin(SurfaceDraft("s$i", project, "terreno$i", "obra$i.xml", 1_000L + i, source, LinearUnit.METRE))
            surfaces.finish("s$i", summary)
        }

        surfaces.surfaces(project).first().associate { it.id to it.source } shouldBe
            sources.mapIndexed { i, source -> "s$i" to source }.toMap()
    }
}

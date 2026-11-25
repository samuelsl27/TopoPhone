package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Al importar: el nombre de una superficie sin nombre, su contorno y que quede visible (S061/RF-1, RF-4, RF-6). */
@RunWith(AndroidJUnit4::class)
class SurfaceImporterNameTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomSurfaceRepository(db)
    private val project = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    /** Una malla de 3 × 3 celdas de 10 m sin la central (un hueco), con el nombre de superficie [name]. */
    private fun landXml(name: String): ByteArray {
        val points = StringBuilder()
        for (j in 0..3) {
            for (i in 0..3) {
                // LandXML: Norte, Este, cota.
                points.append("<P id=\"${j * 4 + i + 1}\">${4_474_000.0 + j * 10} ${440_000.0 + i * 10} ${600.0 + i}</P>\n")
            }
        }
        val faces = StringBuilder()
        for (j in 0..2) {
            for (i in 0..2) {
                if (i == 1 && j == 1) continue
                val a = j * 4 + i + 1
                faces.append("<F>$a ${a + 1} ${a + 5}</F>\n<F>$a ${a + 5} ${a + 4}</F>\n")
            }
        }
        return (
            "<?xml version=\"1.0\"?>\n<LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\">" +
                "<Units><Metric linearUnit=\"meter\"/></Units><Surfaces><Surface name=\"$name\"><Definition surfType=\"TIN\">" +
                "<Pnts>\n$points</Pnts><Faces>\n$faces</Faces></Definition></Surface></Surfaces></LandXML>\n"
            ).toByteArray()
    }

    private fun import(name: String, fileName: String): SurfaceImportResult.Imported = runBlocking {
        val bytes = landXml(name)
        SurfaceImporter(repository).import(project, ProjectCrs.ETRS89_UTM30, fileName, 0, CadSourceCrs.Project, LinearUnit.METRE, {
            ByteArrayInputStream(bytes)
        }).shouldBeInstanceOf<SurfaceImportResult.Imported>()
    }

    @Test
    fun `S061 RF-1 sin nombre en el fichero la superficie se llama como el`() = runBlocking<Unit> {
        val result = import("", "explanacion.xml")

        result.summary.name shouldBe "explanacion"
        repository.surfaces(project).first().single().name shouldBe "explanacion"
    }

    @Test
    fun `S061 RF-1 con nombre en el fichero se conserva`() = runBlocking<Unit> {
        import("rasante", "obra.xml").summary.name shouldBe "rasante"
    }

    @Test
    fun `S061 RF-4 la superficie importada queda visible`() = runBlocking<Unit> {
        import("terreno", "obra.xml")

        repository.surfaces(project).first().single().visible shouldBe true
    }

    @Test
    fun `S061 RF-6 el contorno se guarda al importar con su exterior y su hueco`() = runBlocking<Unit> {
        val id = import("terreno", "obra.xml").surfaceId

        db.surfaces().outlineParts(id).shouldNotBeEmpty()
        val outline = repository.outline(id).shouldNotBeNull()
        outline.rings.map { it.exterior to it.vertexCount }.sortedBy { it.second } shouldBe listOf(false to 4, true to 12)
    }
}

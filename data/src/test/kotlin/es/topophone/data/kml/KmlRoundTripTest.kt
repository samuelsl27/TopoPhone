package es.topophone.data.kml

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.kml.export.KmlExportBuilder
import es.topophone.core.formats.kml.export.KmlExportOptions
import es.topophone.core.formats.kml.export.KmlWriter
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gpkg.GeoPackageImporter
import es.topophone.data.gpkg.GpkgImportOptions
import es.topophone.data.gpkg.GpkgImportResult
import es.topophone.data.gpkg.GpkgLayerChoice
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.StringWriter
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Exportar a KML y volver a importar da las mismas coordenadas a menos de 0,1 mm (constitución nº 2, S031/RF-24): los puntos y la línea
 * salen con 9 decimales de grado (0,055 mm como mucho) y vuelven al sistema del proyecto. Las posiciones son las de pyproj de
 * `fixtures/formatos/shp-exportado/esperado-puntos.csv`.
 */
@RunWith(AndroidJUnit4::class)
class KmlRoundTripTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val cad = RoomCadRepository(db)
    private val crs = ProjectCrs.ETRS89_UTM30
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)

    @After
    fun close() = db.close()

    private val project = Project(
        id = "p1",
        name = "Ida y vuelta",
        author = "",
        description = "",
        crs = crs,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    /** Las filas de pyproj de `esperado-puntos.csv` para ETRS89 UTM 30: nombre, latitud, longitud, este y norte. */
    private val reference: List<List<String>> =
        File(KmlFixtures.folder.parentFile, "shp-exportado/esperado-puntos.csv").readLines().drop(1)
            .map { it.split(",") }.filter { it[0] == "25830" }.map { it.drop(1) }

    private fun point(id: String, name: String, lat: Double, lon: Double, easting: Double, northing: Double, height: Double) = Point(
        id = id,
        projectId = "p1",
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(lat, lon, height + 50.0), easting, northing, height),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
        code = null,
    )

    private val points = reference.mapIndexed { i, r ->
        point("${i + 1}", r[0], r[1].toDouble(), r[2].toDouble(), r[3].toDouble(), r[4].toDouble(), 600.0 + i)
    }

    private fun roundTrip(
        options: KmlExportOptions,
        lines: List<ProjectLine> = emptyList(),
        useElevations: Boolean = true,
    ): List<CadEntity> {
        val export = KmlExportBuilder().build(points, project, options, lines = lines)
        val text = StringWriter().also { KmlWriter.write(export.document, it) }.toString()
        val file = folder.newFile("ida-y-vuelta.kml").also { it.writeText(text, Charsets.UTF_8) }
        val reader = KmlReader(folder.newFolder("cache"))
        val opened = runBlocking { reader.open(KmlInput(file.name, file.length()) { file.inputStream() }, GeodeticDatum.ETRS89) }
        val session = opened.shouldBeInstanceOf<KmlOpenResult.Opened>().session
        session.use {
            val projectId = runBlocking {
                RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Destino", crs = crs))
                    .shouldBeInstanceOf<SaveResult.Saved>().id
            }
            val choices = session.layers.map { GpkgLayerChoice(it, GeodeticSource, "name", wgs84 = true) }
            val result = runBlocking {
                GeoPackageImporter(cad).import(
                    projectId,
                    crs,
                    "ida-y-vuelta.kml",
                    GpkgImportOptions(choices, useElevations, ImportArea.All),
                    session,
                )
            }
            result.shouldBeInstanceOf<GpkgImportResult.Imported>()
            val layers = runBlocking { cad.drawings(projectId).first() }.single().layers
            return layers.flatMap { runBlocking { cad.chunksIn(listOf(it.id), everywhere) }.flatMap { c -> c.entities } }
        }
    }

    @Test
    fun `S031 RF-24 los puntos exportados vuelven al sistema del proyecto a menos de 0,1 mm`() {
        val entities = roundTrip(KmlExportOptions())

        entities.size shouldBe points.size
        var worst = 0.0
        for (p in points) {
            val back = entities.single { it.name == p.name }
            worst = maxOf(worst, abs(back.easting[0] - p.coordinates.easting!!), abs(back.northing[0] - p.coordinates.northing!!))
        }
        println("S031 ida y vuelta de ${points.size} puntos: diferencia máxima ${worst * 1000} mm")
        (worst < 1e-4) shouldBe true
    }

    @Test
    fun `S031 RF-26 con Incluir altura y Si a las altitudes la altura vuelve igual`() {
        val entities = roundTrip(KmlExportOptions(includeHeight = true))

        for (p in points) entities.single { it.name == p.name }.height?.toList() shouldBe listOf(p.coordinates.height)
    }

    @Test
    fun `S031 RF-26 sin Incluir altura no vuelve ninguna cota aunque se usen las altitudes`() {
        val entities = roundTrip(KmlExportOptions(includeHeight = false))

        entities.all { it.height == null } shouldBe true
    }

    @Test
    fun `S031 RF-22 una linea exportada vuelve con sus vertices a menos de 0,1 mm`() {
        val line = ProjectLine(
            id = "l1",
            projectId = "p1",
            name = "Madrid-Valencia-Sevilla",
            code = null,
            geometry = CodeGeometry.LINE,
            closed = false,
            hidden = false,
            active = false,
            vertices = points.map { LineVertex(it.id) },
            createdAtMs = 0,
        )

        val entities = roundTrip(KmlExportOptions(), lines = listOf(line))

        val back = entities.single { it.name == "Madrid-Valencia-Sevilla" }
        back.vertexCount shouldBe points.size
        for ((i, p) in points.withIndex()) {
            (abs(back.easting[i] - p.coordinates.easting!!) < 1e-4) shouldBe true
            (abs(back.northing[i] - p.coordinates.northing!!) < 1e-4) shouldBe true
        }
    }

    private companion object {
        val GeodeticSource = CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
    }
}

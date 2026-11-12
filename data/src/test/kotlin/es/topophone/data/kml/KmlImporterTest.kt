package es.topophone.data.kml

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgLayerInfo
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.gpkg.GeoPackageImporter
import es.topophone.data.gpkg.GpkgImportFailure
import es.topophone.data.gpkg.GpkgImportOptions
import es.topophone.data.gpkg.GpkgImportResult
import es.topophone.data.gpkg.GpkgLayerChoice
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un KML o KMZ con el importador de S048: capas, geometrías, nombre, color, altitudes, ignorados y zona (S031/RF-5 a RF-19). */
@RunWith(AndroidJUnit4::class)
class KmlImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCadRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val crs = ProjectCrs.ETRS89_UTM30
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)
    private val expected = KmlFixtures.expected("esperado.csv")
    private val sessions = ArrayList<KmlSession>()
    private var tick = 0
    private var cacheId = 0

    @After
    fun close() {
        sessions.forEach { it.close() }
        db.close()
    }

    private fun open(path: String): KmlSession {
        val reader = KmlReader(folder.newFolder("c${cacheId++}"))
        val opened = runBlocking { reader.open(KmlFixtures.input(path), GeodeticDatum.ETRS89) }
        return opened.shouldBeInstanceOf<KmlOpenResult.Opened>().session.also { sessions += it }
    }

    private fun importer() = GeoPackageImporter(repository, clock = { 1_792_000_000_000L }, newId = { "id-${tick++}" })

    private fun choices(session: KmlSession, nameField: String? = "name") = session.layers.map { layer ->
        val supported = session.crs(layer).shouldBeInstanceOf<GpkgCrs.Supported>()
        GpkgLayerChoice(layer, supported.source, nameField, wgs84 = supported.wgs84)
    }

    private fun import(
        session: KmlSession,
        useElevations: Boolean = true,
        area: ImportArea = ImportArea.All,
        selected: List<GpkgLayerChoice> = choices(session),
    ): GpkgImportResult = runBlocking {
        importer().import(projectId, crs, "obra.kml", GpkgImportOptions(selected, useElevations, area), session)
    }

    private fun drawing() = runBlocking { repository.drawings(projectId).first() }.single()

    private fun entities(table: String, kind: DesignLayerKind): List<CadEntity> {
        val layer = drawing().layers.first { it.sourceTable == table && it.kind == kind }
        return runBlocking { repository.chunksIn(listOf(layer.id), everywhere) }.flatMap { it.entities }
    }

    private fun nothingSaved() = listOf("cad_drawings", "cad_layers", "cad_chunks", "cad_chunk_attributes").forEach {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $it").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) shouldBe 0
        }
    }

    private fun rowsOf(file: String, layer: String) =
        expected.filter { it["archivo"] == file && it["capa"] == layer }.groupBy { it.getValue("fid").toInt() }

    private val peninsular = listOf(
        "carpetas-anidadas.kml", "geometrias.kml", "atributos.kml", "estilos.kml", "altitudes.kml", "ignorados.kml", "fuera-de-ambito.kml",
        "con-doc.kmz", "enlaces-internos.kmz", "sin-doc.kmz",
    )

    private fun kindOf(layer: GpkgLayerInfo) = if (layer.geometryType == "POINT") DesignLayerKind.POINTS else DesignLayerKind.LINES

    /** Los elementos esperados de un objeto: sus vértices en el proyecto (e, n, z). */
    private fun wanted(parts: List<Map<String, String>>) = parts.map { row ->
        row.getValue("vertices_proyecto").split(';').map { v ->
            val c = v.split(' ')
            Triple(c[0].toDouble(), c[1].toDouble(), c[2].takeIf { it != "_" }?.toDouble())
        }
    }

    /** El elemento guardado tiene los vértices esperados a menos de 1 mm y la cota solo donde el KML la declara. */
    private fun matches(actual: CadEntity, element: List<Triple<Double, Double, Double?>>, withElevations: Boolean): Boolean {
        if (actual.vertexCount != element.size) return false
        return element.indices.all { i ->
            val z = element[i].third
            abs(actual.easting[i] - element[i].first) < 1e-3 &&
                abs(actual.northing[i] - element[i].second) < 1e-3 &&
                if (withElevations && z != null) actual.height?.get(i) == z else actual.height == null
        }
    }

    private fun colorOf(text: String): CadColor? = text.takeIf { it.isNotEmpty() }?.let { CadColor.rgb(it.removePrefix("#").toInt(16)) }

    @Test
    fun `S031 RF-7 y RF-8 lo que entra de cada fichero coincide con lo esperado vertice a vertice a menos de 1 mm`() {
        var checked = 0
        for (file in peninsular) {
            val session = open(file)
            val result = import(session)
            result.shouldBeInstanceOf<GpkgImportResult.Imported>()
            for (layer in session.layers) {
                val perFeature = rowsOf(file, layer.table)
                val ok = perFeature.filterValues { it.first().getValue("estado") == "OK" }.toSortedMap()
                if (ok.isEmpty()) continue
                // el importador guarda los elementos por zonas, no en el orden del fichero: cada esperado casa con uno guardado
                val left = entities(layer.table, kindOf(layer)).toMutableList()
                val rows = ok.values.flatten()
                left.size shouldBe rows.size
                for (row in rows) {
                    val element = wanted(listOf(row)).single()
                    val index = left.indexOfFirst { matches(it, element, withElevations = true) }
                    (index >= 0) shouldBe true
                    left[index].color shouldBe colorOf(row.getValue("color"))
                    left[index].polygonRing shouldBe (row.getValue("contorno") == "SI")
                    left.removeAt(index)
                    checked++
                }
            }
            runBlocking { repository.deleteDrawing(drawing().drawing.id) }
        }
        (checked > 70) shouldBe true
    }

    @Test
    fun `S031 RF-9 el resumen cuenta por capa los importados, las geometrias vacias y las que caen fuera del ambito`() {
        val session = open("ignorados.kml")
        val summary = import(session).shouldBeInstanceOf<GpkgImportResult.Imported>().summary

        val points = summary.layers.first { it.table == "Mezcla (puntos)" }
        points.imported shouldBe 2
        points.ignored shouldBe mapOf(GpkgIgnoredReason.EMPTY_GEOMETRY to 2)
        val lines = summary.layers.first { it.table == "Mezcla (líneas)" }
        lines.imported shouldBe 0
        lines.ignored shouldBe mapOf(GpkgIgnoredReason.EMPTY_GEOMETRY to 3)
        summary.ignoredElements shouldBe mapOf(
            GpkgIgnoredReason.NETWORK_LINK to 2,
            GpkgIgnoredReason.OVERLAY to 3,
            GpkgIgnoredReason.MODEL to 2,
            GpkgIgnoredReason.TRACK to 2,
            GpkgIgnoredReason.TOUR to 1,
        )
    }

    @Test
    fun `S031 RF-9 un objeto con un vertice fuera del ambito del sistema del proyecto se ignora entero`() {
        val session = open("fuera-de-ambito.kml")
        val summary = import(session).shouldBeInstanceOf<GpkgImportResult.Imported>().summary

        summary.layers.first { it.table == "Ambito (puntos)" }.ignored shouldBe mapOf(GpkgIgnoredReason.OUTSIDE_CRS_AREA to 1)
        summary.layers.first { it.table == "Ambito (líneas)" }.ignored shouldBe mapOf(GpkgIgnoredReason.OUTSIDE_CRS_AREA to 1)
        summary.importedTotal shouldBe 2
    }

    @Test
    fun `S031 RF-18 el resumen avisa de que todas las capas son WGS84 tomado como el datum del proyecto`() {
        val session = open("carpetas-anidadas.kml")

        val summary = import(session).shouldBeInstanceOf<GpkgImportResult.Imported>().summary

        summary.wgs84Layers shouldBe session.layers.map { it.table }
    }

    @Test
    fun `S031 RF-10 el nombre de la marca es el del objeto y sin nombre su numero en la capa`() {
        val session = open("geometrias.kml")

        import(session)

        // un elemento por parte: «Dos puntos» y «Anidada» tienen dos
        entities("Geometrias (puntos)", DesignLayerKind.POINTS).map { it.name.orEmpty() }.sorted() shouldBe
            listOf("4", "Anidada", "Anidada", "Dos puntos", "Dos puntos", "Mixta", "Punto")
        entities("Geometrias (líneas)", DesignLayerKind.LINES).map { it.name }.toSet().contains("Linea") shouldBe true
    }

    @Test
    fun `S031 RF-10 sin campo de nombre todos los objetos se nombran por su numero`() {
        val session = open("geometrias.kml")

        import(session, selected = choices(session, nameField = null))

        val names = entities("Geometrias (puntos)", DesignLayerKind.POINTS).map { it.name.orEmpty() }

        names.sorted() shouldBe listOf("1", "2", "2", "3", "4", "5", "5")
        drawing().layers.all { it.nameField == null } shouldBe true
    }

    @Test
    fun `S031 RF-13 con Si a las altitudes las marcas absolutas guardan su cota y con No ninguna`() {
        val withElevations = open("altitudes.kml")
        import(withElevations)
        val points = entities("Altitudes (puntos)", DesignLayerKind.POINTS)
        points.first().height?.toList() shouldBe listOf(646.377)
        points.drop(1).all { it.height == null } shouldBe true
        drawing().drawing.hasElevations shouldBe true
        runBlocking { repository.deleteDrawing(drawing().drawing.id) }

        import(open("altitudes.kml"), useElevations = false)

        entities("Altitudes (puntos)", DesignLayerKind.POINTS).all { it.height == null } shouldBe true
        drawing().drawing.hasElevations shouldBe false
    }

    @Test
    fun `S031 RF-14 las marcas pegadas al suelo o relativas se importan sin cota aunque lleven altitud`() {
        val session = open("altitudes.kml")

        import(session, useElevations = true)

        val names = listOf("Abs punto", "Abs sin z", "Clamp con z", "Fondo marino", "Mixta", "Mixta", "Relativa", "Sin modo con z")
        val points = entities("Altitudes (puntos)", DesignLayerKind.POINTS)
        points.map { it.name.orEmpty() }.sorted() shouldBe names
        points.mapNotNull { it.height }.size shouldBe 1
    }

    @Test
    fun `S031 RF-5 un poligono entra como una linea cerrada por anillo con la marca de contorno`() {
        val session = open("geometrias.kml")

        import(session)

        val lines = entities("Geometrias (líneas)", DesignLayerKind.LINES)
        lines.filter { it.name == "Parcela con dos huecos" }.map { it.polygonRing } shouldBe listOf(true, true, true)
        lines.filter { it.name == "Anillo" }.map { it.polygonRing } shouldBe listOf(false)
    }

    @Test
    fun `S031 RF-15 la zona de importacion deja fuera los objetos sin ningun vertice dentro`() {
        val session = open("carpetas-anidadas.kml")
        val center = CentralPoint(40.4168, -3.7038)

        val result = import(session, area = ImportArea.Circle(center, 100.0))

        result.shouldBeInstanceOf<GpkgImportResult.Imported>()
        entities("carpetas-anidadas (puntos)", DesignLayerKind.POINTS).map { it.name } shouldBe listOf("Suelto A")
    }

    @Test
    fun `S031 RF-17 si no entra ningun objeto no se guarda nada`() {
        val session = open("fuera-de-ambito.kml")
        val onlyOutside = choices(session).filter { it.layer.table == "Ambito (puntos)" }

        val far = ImportArea.Circle(CentralPoint(37.0, -6.0), 100.0)
        val result = import(session, area = far, selected = onlyOutside)

        result.shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.NO_OBJECTS
        nothingSaved()
    }

    @Test
    fun `S031 RF-19 el dibujo guarda el origen KML y el nombre del fichero`() {
        val session = open("con-doc.kmz")

        import(session)

        drawing().drawing.origin shouldBe CadOrigin.KML
        drawing().drawing.fileName shouldBe "obra.kml"
    }
}

package es.topophone.data.shp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadOrigin
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.cad.GpkgAttributesCodec
import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgValue
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.cad.CadChunkWrite
import es.topophone.data.cad.CadRepository
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
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un Shapefile con el importador de S048: sistema, zona, polígonos, cotas, nombre, atributos, ignorados y limpieza (S030/RF-8 a RF-30). */
@RunWith(AndroidJUnit4::class)
class ShapefileImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCadRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Parcelas")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val crs = ProjectCrs.ETRS89_UTM30
    private val utm30 = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
    private val expected = ShpFixtures.expected("esperado.csv")
    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)
    private val sessions = ArrayList<ShapefileSession>()
    private var tick = 0
    private var cacheId = 0

    @After
    fun close() {
        sessions.forEach { it.close() }
        db.close()
    }

    /** Abre las capas de un escenario como ficheros sueltos. */
    private fun session(scenario: String, vararg layers: String): ShapefileSession {
        val files = File(ShpFixtures.folder, scenario).listFiles().orEmpty()
            .filter { f -> layers.any { f.nameWithoutExtension.equals(it, ignoreCase = true) } }
            .map { "$scenario/${it.name}" }
        val reader = ShapefileReader(folder.newFolder("c${cacheId++}"), freeSpace = { Long.MAX_VALUE })
        return runBlocking { reader.open(files.map { ShpFixtures.input(it) }) }
            .shouldBeInstanceOf<ShpOpenResult.Opened>().session.also { sessions += it }
    }

    private fun importer(repo: CadRepository = repository, maxObjects: Int = ImportArea.MAX_OBJECTS) =
        GeoPackageImporter(repo, clock = { 1_792_000_000_000L }, newId = { "id-${tick++}" }, maxObjects = maxObjects)

    private fun choice(session: ShapefileSession, table: String, source: CadSourceCrs = utm30, nameField: String? = "nombre") =
        GpkgLayerChoice(session.layers.first { it.table == table }, source, nameField)

    private fun import(
        session: ShapefileSession,
        vararg choices: GpkgLayerChoice,
        useElevations: Boolean = true,
        area: ImportArea = ImportArea.All,
        importer: GeoPackageImporter = importer(),
    ): GpkgImportResult = runBlocking {
        importer.import(projectId, crs, "parcelas.zip", GpkgImportOptions(choices.toList(), useElevations, area), session)
    }

    private fun imported(result: GpkgImportResult) = result.shouldBeInstanceOf<GpkgImportResult.Imported>()

    private fun drawing() = runBlocking { repository.drawings(projectId).first() }.single()

    private fun entities(layerId: String): List<CadEntity> = runBlocking {
        repository.chunksIn(listOf(layerId), everywhere)
    }.flatMap { it.entities }

    private fun layerOf(table: String, kind: DesignLayerKind) = drawing().layers.first { it.sourceTable == table && it.kind == kind }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    private fun nothingSaved() = listOf("cad_drawings", "cad_layers", "cad_chunks", "cad_chunk_attributes").forEach { count(it) shouldBe 0 }

    /** Los elementos esperados de una capa: sus vértices en el proyecto (e, n, z). */
    private fun expectedElements(scenario: String, layer: String): List<List<Triple<Double, Double, Double?>>> = expected
        .filter { it["escenario"] == scenario && it["capa"] == layer && it["estado"] == "OK" }
        .map { row ->
            row.getValue("vertices_proyecto").split(';').map { v ->
                val c = v.split(' ')
                Triple(c[0].toDouble(), c[1].toDouble(), c[2].takeIf { it != "_" }?.toDouble())
            }
        }

    /** Cada elemento esperado casa con uno guardado, vértice a vértice a menos de 1 mm, y no sobra ninguno. */
    private fun assertSameElements(actual: List<CadEntity>, wanted: List<List<Triple<Double, Double, Double?>>>) {
        actual.size shouldBe wanted.size
        val left = actual.toMutableList()
        for (element in wanted) {
            val index = left.indexOfFirst { e ->
                e.vertexCount == element.size &&
                    element.indices.all { i ->
                        abs(e.easting[i] - element[i].first) < 1e-3 &&
                            abs(e.northing[i] - element[i].second) < 1e-3 &&
                            (element[i].third == null || (e.height?.get(i) ?: Double.NaN) == element[i].third)
                    }
            }
            (index >= 0) shouldBe true
            left.removeAt(index)
        }
    }

    @Test
    fun `S030 RF-26 los puntos de una capa entran con su nombre y a menos de 1 mm`() {
        val session = session("puntos-25830", "vertices")

        val result = imported(import(session, choice(session, "vertices")))

        result.summary.importedTotal shouldBe 5
        drawing().drawing.origin shouldBe CadOrigin.SHAPEFILE
        drawing().drawing.fileName shouldBe "parcelas.zip"
        val points = entities(layerOf("vertices", DesignLayerKind.POINTS).id)
        assertSameElements(points, expectedElements("puntos-25830", "vertices"))
        points.map { it.name }.toSet() shouldBe setOf("Esquina norte", "Árbol ñandú", "3", "4", "Farola")
        points.all { it.kind == CadEntityKind.POINT && !it.polygonRing } shouldBe true
    }

    @Test
    fun `S030 RF-22 sin campo de nombre o con valor vacio se nombra por el numero de registro`() {
        val session = session("puntos-25830", "vertices")

        import(session, choice(session, "vertices", nameField = null))

        entities(layerOf("vertices", DesignLayerKind.POINTS).id).map { it.name }.toSet() shouldBe setOf("1", "2", "3", "4", "5")
        drawing().layers.single().nameField shouldBe null
        drawing().layers.single().sourceTable shouldBe "vertices"
    }

    @Test
    fun `S030 RF-15 un poligono entra como una linea cerrada por anillo con la marca de contorno`() {
        val session = session("poligonos-25830", "parcelas", "manzanas")

        imported(import(session, choice(session, "parcelas"), choice(session, "manzanas")))

        val parcelas = entities(layerOf("parcelas", DesignLayerKind.LINES).id)
        assertSameElements(parcelas, expectedElements("poligonos-25830", "parcelas"))
        parcelas.all { it.polygonRing && it.kind == CadEntityKind.POLYLINE } shouldBe true
        parcelas.map { it.name } shouldBe listOf("Parcela 1", "Parcela 2", "Parcela 2", "Parcela 2")
        val manzanas = entities(layerOf("manzanas", DesignLayerKind.LINES).id)
        assertSameElements(manzanas, expectedElements("poligonos-25830", "manzanas"))
        manzanas.size shouldBe 4
    }

    @Test
    fun `S030 RF-15 una linea de varias partes y un multipunto entran como un elemento por parte`() {
        val lines = session("lineas-4258", "ejes")
        val geographic = CadSourceCrs.Geographic(GeodeticDatum.ETRS89)

        imported(import(lines, choice(lines, "ejes", geographic)))

        assertSameElements(entities(layerOf("ejes", DesignLayerKind.LINES).id), expectedElements("lineas-4258", "ejes"))
        entities(layerOf("ejes", DesignLayerKind.LINES).id).size shouldBe 4
    }

    @Test
    fun `S030 RF-15 un multipunto da un punto por punto`() {
        val session = session("multipuntos-25830", "multipuntos")

        imported(import(session, choice(session, "multipuntos")))

        val points = entities(layerOf("multipuntos", DesignLayerKind.POINTS).id)
        assertSameElements(points, expectedElements("multipuntos-25830", "multipuntos"))
        points.size shouldBe 4
    }

    @Test
    fun `S030 RF-17 la Z se guarda sin convertir con las cotas y sin ellas no hay altura y la M no cuenta`() {
        val session = session("altura-25830", "lineas_z", "lineas_m", "puntos_z", "poligonos_z")

        imported(
            import(session, choice(session, "lineas_z"), choice(session, "lineas_m"), choice(session, "puntos_z"), useElevations = true),
        )

        entities(layerOf("lineas_z", DesignLayerKind.LINES).id).single().height?.toList() shouldBe listOf(100.0, 101.0, 102.0)
        entities(layerOf("lineas_m", DesignLayerKind.LINES).id).single().height shouldBe null
        entities(layerOf("puntos_z", DesignLayerKind.POINTS).id).map { it.height?.single() } shouldBe listOf(646.377, 0.0)
        drawing().drawing.hasElevations shouldBe true
        db.openHelper.writableDatabase.execSQL("DELETE FROM cad_drawings")

        val again = session("altura-25830", "lineas_z")
        imported(import(again, choice(again, "lineas_z"), useElevations = false))

        entities(layerOf("lineas_z", DesignLayerKind.LINES).id).single().height shouldBe null
        drawing().drawing.hasElevations shouldBe false
    }

    @Test
    fun `S030 RF-21 los atributos de cada objeto se guardan con su tipo`() {
        val session = session("puntos-25830", "vertices")

        imported(import(session, choice(session, "vertices")))

        val block = db.openHelper.readableDatabase.query("SELECT data FROM cad_chunk_attributes").use { cursor ->
            buildList { while (cursor.moveToNext()) add(GpkgAttributesCodec.decode(cursor.getBlob(0))) }
        }.single()
        block.columns shouldBe listOf("nombre", "cota", "codigo", "fecha", "activo")
        block.rowCount shouldBe 5
        val chunk = runBlocking { repository.chunksIn(listOf(layerOf("vertices", DesignLayerKind.POINTS).id), everywhere) }.single()
        val byName = chunk.entities.indices.associate { chunk.entities[it].name to block.row(it) }
        byName.getValue("Esquina norte") shouldBe linkedMapOf(
            "nombre" to GpkgValue.Text("Esquina norte"),
            "cota" to GpkgValue.Real(646.377),
            "codigo" to GpkgValue.Integer(1),
            "fecha" to GpkgValue.Date("2026-10-01"),
            "activo" to GpkgValue.Bool(true),
        )
        byName.getValue("3")["nombre"] shouldBe GpkgValue.Null
    }

    @Test
    fun `S030 RF-16 los registros borrados los vacios y los MultiPatch se cuentan con su motivo`() {
        val session = session("borrados-y-nulos", "borrados", "nulos", "multipatch")

        val summary = imported(
            import(session, choice(session, "borrados"), choice(session, "nulos"), choice(session, "multipatch")),
        ).summary

        val borrados = summary.layers.first { it.table == "borrados" }
        borrados.imported shouldBe 3
        borrados.ignored shouldBe mapOf(GpkgIgnoredReason.DELETED_RECORD to 2)
        val nulos = summary.layers.first { it.table == "nulos" }
        nulos.imported shouldBe 2
        nulos.ignored shouldBe mapOf(GpkgIgnoredReason.EMPTY_GEOMETRY to 2)
        val patches = summary.layers.first { it.table == "multipatch" }
        patches.imported shouldBe 0
        patches.ignored shouldBe mapOf(GpkgIgnoredReason.MULTIPATCH to 2)
        summary.ignoredTotal shouldBe 6
        (summary.layers.sumOf { it.outsideArea }) shouldBe 0
    }

    @Test
    fun `S030 RF-16 una capa solo de MultiPatch no deja nada guardado`() {
        val session = session("borrados-y-nulos", "multipatch")

        val result = import(session, choice(session, "multipatch"))

        result.shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.NO_OBJECTS
        nothingSaved()
    }

    @Test
    fun `S030 RF-20 los objetos sin registro en un dbf corto se importan sin atributos y se cuentan`() {
        val session = session("incompletos", "dbf_corto", "dbf_largo", "sin_dbf")

        val summary = imported(
            import(session, choice(session, "dbf_corto"), choice(session, "dbf_largo"), choice(session, "sin_dbf")),
        ).summary

        summary.layers.first { it.table == "dbf_corto" }.imported shouldBe 5
        summary.layers.first { it.table == "dbf_corto" }.withoutAttributes shouldBe 2
        summary.layers.first { it.table == "dbf_largo" }.withoutAttributes shouldBe 0
        summary.layers.first { it.table == "sin_dbf" }.imported shouldBe 5
        summary.layers.first { it.table == "sin_dbf" }.withoutAttributes shouldBe 0
        summary.withoutAttributesTotal shouldBe 2
        val short = entities(layerOf("dbf_corto", DesignLayerKind.POINTS).id)
        short.map { it.name }.toSet() shouldBe setOf("Esquina norte", "Árbol ñandú", "3", "4", "5")
    }

    @Test
    fun `S030 RF-13 solo entran los objetos con algun vertice dentro de la zona y el resto cuenta como fuera`() {
        val session = session("puntos-25830", "vertices")
        val center = Utm.inverse(440_100.0, 4_474_100.0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }

        val summary = imported(import(session, choice(session, "vertices"), area = ImportArea.Circle(center, 100.0))).summary

        summary.layers.single().imported shouldBe 2 // a 0 m y a 54 m; el tercero está a 112 m
        summary.layers.single().outsideArea shouldBe 3
        entities(layerOf("vertices", DesignLayerKind.POINTS).id).map { it.name }.toSet() shouldBe setOf("Esquina norte", "Árbol ñandú")
    }

    @Test
    fun `S030 RF-16 un vertice fuera del ambito descarta el objeto entero`() {
        val session = session("ambito", "fuera")
        val geographic = CadSourceCrs.Geographic(GeodeticDatum.ETRS89)

        val summary = imported(import(session, choice(session, "fuera", geographic))).summary

        summary.layers.single().imported shouldBe 2
        summary.layers.single().ignored shouldBe mapOf(GpkgIgnoredReason.OUTSIDE_CRS_AREA to 1)
    }

    @Test
    fun `S030 RF-8 las capas en otros sistemas entran en UTM 30 a menos de 1 mm`() {
        val session = session("sistemas", "utm29", "utm31", "ed50_utm30", "geograficas_4258", "wgs84_4326")
        val sources = mapOf(
            "utm29" to CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
            "utm31" to CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31),
            "ed50_utm30" to CadSourceCrs.Utm(GeodeticDatum.ED50, 30),
            "geograficas_4258" to CadSourceCrs.Geographic(GeodeticDatum.ETRS89),
            "wgs84_4326" to CadSourceCrs.Geographic(GeodeticDatum.ETRS89),
        )

        imported(import(session, *sources.map { (table, source) -> choice(session, table, source) }.toTypedArray()))

        for (table in sources.keys) {
            val wanted = expectedElements("sistemas", table)
            wanted.size shouldBe 3
            assertSameElements(entities(layerOf(table, DesignLayerKind.POINTS).id), wanted)
        }
    }

    @Test
    fun `S030 RF-18 el texto de un dbf en Windows-1252 sin cpg se importa con sus tildes`() {
        val session = session("codificaciones", "w1252_sin_cpg")

        imported(import(session, choice(session, "w1252_sin_cpg")))

        entities(layerOf("w1252_sin_cpg", DesignLayerKind.POINTS).id).map { it.name } shouldBe
            listOf("Árbol ñandú", "Camión", "Plaza Mayor", "Sin tildes")
    }

    @Test
    fun `S030 RF-29 el progreso llega al total de objetos de las capas marcadas`() {
        val session = session("poligonos-25830", "parcelas", "manzanas")
        val seen = mutableListOf<Pair<Long, Long>>()

        runBlocking {
            importer().import(
                projectId,
                crs,
                "parcelas.zip",
                GpkgImportOptions(listOf(choice(session, "parcelas"), choice(session, "manzanas"))),
                session,
            ) { done, total -> seen += done to total }
        }

        seen.first() shouldBe (0L to 4L)
        seen.last() shouldBe (4L to 4L)
    }

    @Test
    fun `S030 RF-30 pasar del limite de objetos o no entrar ninguno no guarda nada`() {
        val session = session("puntos-25830", "vertices")
        import(session, choice(session, "vertices"), importer = importer(maxObjects = 3))
            .shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.TOO_MANY_OBJECTS
        nothingSaved()

        val far = CentralPoint(37.3891, -5.9845) // Sevilla: ningún punto de Madrid cae a 5 km
        import(session, choice(session, "vertices"), area = ImportArea.Circle(far, 5_000.0))
            .shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.NO_OBJECTS
        nothingSaved()
    }

    @Test
    fun `S030 RF-30 si falla la escritura no queda nada y lo dice`() {
        val session = session("puntos-25830", "vertices")
        val failing = object : CadRepository by repository {
            override suspend fun addChunks(chunks: List<CadChunkWrite>) = throw IllegalStateException("disco lleno")
        }

        import(session, choice(session, "vertices"), importer = importer(failing))
            .shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.WRITE_ERROR
        nothingSaved()
    }

    @Test
    fun `S030 RF-30 cancelar no guarda nada del dibujo`() {
        val session = session("puntos-25830", "vertices")
        val cancelling = object : CadRepository by repository {
            override suspend fun addLayer(layer: es.topophone.core.model.cad.DesignLayer) {
                repository.addLayer(layer)
                throw CancellationException("cancelado por el usuario")
            }
        }

        val thrown = runCatching { import(session, choice(session, "vertices"), importer = importer(cancelling)) }

        (thrown.exceptionOrNull() is CancellationException) shouldBe true
        nothingSaved()
    }
}

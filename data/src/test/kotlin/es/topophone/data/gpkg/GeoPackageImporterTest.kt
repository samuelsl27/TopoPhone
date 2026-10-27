package es.topophone.data.gpkg

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
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar capas de un GeoPackage como un dibujo: reproyección, zona, polígonos, cotas, nombres, atributos y limpieza (S048/RF-8 a RF-30). */
@RunWith(AndroidJUnit4::class)
class GeoPackageImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val repository = RoomCadRepository(db)
    private val projectId = runBlocking {
        RoomProjectRepository(
            db,
        ).create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }
    private val crs = ProjectCrs.ETRS89_UTM30
    private val utm30 = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
    private val expected = GpkgFixtures.expected("esperado.csv")
    private val e0 = 440_000.0
    private val n0 = 4_474_000.0
    private val center = Utm.inverse(e0, n0, 30).let { CentralPoint(it.latitudeDeg, it.longitudeDeg) }
    private var tick = 0

    @After
    fun close() = db.close()

    private fun session(name: String): GpkgSession {
        val bytes = GpkgFixtures.bytes(name)
        val result = runBlocking { GeoPackageReader(folder.newFolder()).open(bytes.size.toLong()) { ByteArrayInputStream(bytes) } }
        return result.shouldBeInstanceOf<GpkgOpenResult.Opened>().session
    }

    private fun importer(repo: CadRepository = repository, maxObjects: Int = ImportArea.MAX_OBJECTS) =
        GeoPackageImporter(repo, clock = { 1_792_000_000_000L }, newId = { "id-${tick++}" }, maxObjects = maxObjects)

    private fun choice(session: GpkgSession, table: String, source: CadSourceCrs = utm30, nameField: String? = "nombre") =
        GpkgLayerChoice(session.layers.first { it.table == table }, source, nameField)

    private fun import(
        session: GpkgSession,
        vararg choices: GpkgLayerChoice,
        useElevations: Boolean = true,
        area: ImportArea = ImportArea.All,
        importer: GeoPackageImporter = importer(),
        project: ProjectCrs = crs,
    ): GpkgImportResult = runBlocking {
        importer.import(projectId, project, "capas.gpkg", GpkgImportOptions(choices.toList(), useElevations, area), session)
    }

    private fun imported(result: GpkgImportResult) = result.shouldBeInstanceOf<GpkgImportResult.Imported>()

    private val everywhere = CadExtent(-1e9, 1e9, -1e9, 1e9)

    private fun drawing() = runBlocking { repository.drawings(projectId).first() }.single()

    /** Los elementos de una capa de diseño ya guardados. */
    private fun entities(layerId: String): List<CadEntity> =
        runBlocking { repository.chunksIn(listOf(layerId), everywhere) }.flatMap { it.entities }

    private fun layerOf(table: String, kind: DesignLayerKind) = drawing().layers.first { it.sourceTable == table && it.kind == kind }

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    /** Los elementos esperados de una tabla: sus vértices en el proyecto (e, n, z), por elemento. */
    private fun expectedElements(file: String, table: String): List<List<Triple<Double, Double, Double?>>> = expected
        .filter { it["archivo"] == file && it["tabla"] == table && it["estado"] == "OK" }
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
    fun `S048 RF-17 los puntos de una capa entran con su nombre y a menos de 1 mm`() {
        session("puntos-25830.gpkg").use { session ->
            val result = imported(import(session, choice(session, "vertices")))

            result.summary.importedTotal shouldBe 5
            drawing().drawing.origin shouldBe CadOrigin.GEOPACKAGE
            drawing().drawing.fileName shouldBe "capas.gpkg"
            val layer = layerOf("vertices", DesignLayerKind.POINTS)
            val points = entities(layer.id)
            assertSameElements(points, expectedElements("puntos-25830.gpkg", "vertices"))
            points.map { it.name }.toSet() shouldBe setOf("Esquina norte", "Árbol ñandú", "3", "4", "Farola")
            points.all { it.kind == CadEntityKind.POINT && !it.polygonRing } shouldBe true
        }
    }

    @Test
    fun `S048 RF-22 sin campo de nombre o con valor vacio se nombra por su numero`() {
        session("puntos-25830.gpkg").use { session ->
            import(session, choice(session, "vertices", nameField = null))

            val names = entities(layerOf("vertices", DesignLayerKind.POINTS).id).map { it.name }

            names.toSet() shouldBe setOf("1", "2", "3", "4", "5")
            drawing().layers.single().nameField shouldBe null
            drawing().layers.single().sourceTable shouldBe "vertices"
        }
    }

    @Test
    fun `S048 RF-8 las lineas en geograficas entran en UTM a menos de 1 mm`() {
        session("lineas-4258.gpkg").use { session ->
            val source = CadSourceCrs.Geographic(GeodeticDatum.ETRS89)

            imported(import(session, choice(session, "ejes", source)))

            assertSameElements(entities(layerOf("ejes", DesignLayerKind.LINES).id), expectedElements("lineas-4258.gpkg", "ejes"))
        }
    }

    @Test
    fun `S048 RF-17 un poligono con huecos da un contorno y un elemento por hueco`() {
        session("poligonos-25830.gpkg").use { session ->
            imported(import(session, choice(session, "parcelas"), choice(session, "manzanas")))

            val parcelas = entities(layerOf("parcelas", DesignLayerKind.LINES).id)
            val manzanas = entities(layerOf("manzanas", DesignLayerKind.LINES).id)

            assertSameElements(parcelas, expectedElements("poligonos-25830.gpkg", "parcelas"))
            assertSameElements(manzanas, expectedElements("poligonos-25830.gpkg", "manzanas"))
            parcelas.size shouldBe 4
            parcelas.all { it.polygonRing && it.kind == CadEntityKind.POLYLINE } shouldBe true
            parcelas.filter { it.name == "Parcela 2" }.size shouldBe 3
            manzanas.map { it.name }.toSet() shouldBe setOf("Manzana A", "Manzana B")
        }
    }

    @Test
    fun `S048 RF-17 una tabla de varios tipos da una capa de puntos y otra de lineas`() {
        session("multiples-25830.gpkg").use { session ->
            val summary = imported(
                import(session, choice(session, "mixta"), choice(session, "multipuntos"), choice(session, "multilineas")),
            ).summary

            summary.importedTotal shouldBe 5
            val kinds = drawing().layers.map { it.sourceTable to it.kind }
            kinds.toSet() shouldBe setOf(
                "mixta" to DesignLayerKind.POINTS,
                "mixta" to DesignLayerKind.LINES,
                "multipuntos" to DesignLayerKind.POINTS,
                "multilineas" to DesignLayerKind.LINES,
            )
            entities(layerOf("multipuntos", DesignLayerKind.POINTS).id).size shouldBe 3
            entities(layerOf("mixta", DesignLayerKind.POINTS).id).size shouldBe 2
            // La línea, la línea de la colección y el contorno y el hueco de su polígono.
            entities(layerOf("mixta", DesignLayerKind.LINES).id).size shouldBe 4
            drawing().layers.map { it.color.argb }.toSet().size shouldBe 4
            drawing().layers.map { it.position } shouldBe listOf(0, 1, 2, 3)
        }
    }

    @Test
    fun `S048 RF-19 con cotas se guarda la altura y M se descarta`() {
        session("altura-25830.gpkg").use { session ->
            val choices = listOf("puntos_z", "lineas_z", "lineas_m", "lineas_zm", "poligonos_z").map { choice(session, it) }

            imported(import(session, *choices.toTypedArray(), useElevations = true))

            val z = entities(layerOf("puntos_z", DesignLayerKind.POINTS).id)
            z.map { it.height?.single() }.toSet() shouldBe setOf(646.377, 0.0)
            entities(layerOf("lineas_z", DesignLayerKind.LINES).id).single().height!!.toList() shouldBe listOf(100.0, 101.0, 102.0)
            entities(layerOf("lineas_m", DesignLayerKind.LINES).id).single().hasZ shouldBe false
            entities(layerOf("lineas_zm", DesignLayerKind.LINES).id).single().height!!.toList() shouldBe listOf(100.0, 101.0, 102.0)
            drawing().drawing.hasElevations shouldBe true
        }
    }

    @Test
    fun `S048 RF-19 sin usar las cotas no se guarda ninguna altura`() {
        session("altura-25830.gpkg").use { session ->
            imported(import(session, choice(session, "lineas_z"), choice(session, "puntos_z"), useElevations = false))

            entities(layerOf("lineas_z", DesignLayerKind.LINES).id).single().hasZ shouldBe false
            entities(layerOf("puntos_z", DesignLayerKind.POINTS).id).all { !it.hasZ } shouldBe true
            drawing().drawing.hasElevations shouldBe false
            drawing().drawing.useElevations shouldBe false
        }
    }

    @Test
    fun `S048 RF-19 si todas las alturas valen 0 el dibujo no tiene cotas`() {
        session("altura-25830.gpkg").use { session ->
            // puntos_z trae 646.377 y 0.0; con solo el segundo no habría cota. Aquí: una capa sin Z alguna.
            imported(import(session, choice(session, "lineas_m")))

            drawing().drawing.hasElevations shouldBe false
        }
    }

    @Test
    fun `S048 RF-15 solo entran los objetos con algun vertice en la zona`() {
        session("puntos-25830.gpkg").use { session ->
            val summary = imported(import(session, choice(session, "vertices"), area = ImportArea.Circle(center, 250.0))).summary

            val wantedFids = expected.filter { it["tabla"] == "vertices" }.filter { row ->
                row.getValue("vertices_proyecto").split(';').any { v ->
                    val c = v.split(' ')
                    hypot(c[0].toDouble() - e0, c[1].toDouble() - n0) <= 250.0
                }
            }.size
            summary.importedTotal shouldBe wantedFids
            summary.layers.single().outsideArea shouldBe 5 - wantedFids
            entities(layerOf("vertices", DesignLayerKind.POINTS).id).size shouldBe wantedFids
        }
    }

    @Test
    fun `S048 RF-15 un objeto entra entero aunque solo un vertice este dentro`() {
        session("poligonos-25830.gpkg").use { session ->
            imported(import(session, choice(session, "parcelas"), area = ImportArea.Circle(center, 160.0)))

            val parcelas = entities(layerOf("parcelas", DesignLayerKind.LINES).id)

            parcelas.map { it.name }.toSet() shouldBe setOf("Parcela 1", "Parcela 2")
            parcelas.size shouldBe 4
        }
    }

    @Test
    fun `S048 RF-18 vacios y curvas se ignoran con su motivo`() {
        session("curvas-y-vacios-25830.gpkg").use { session ->
            val summary = imported(import(session, choice(session, "raros"))).summary

            val layer = summary.layers.single()
            layer.imported shouldBe 2
            layer.ignored shouldBe mapOf(GpkgIgnoredReason.EMPTY_GEOMETRY to 3, GpkgIgnoredReason.CURVE_GEOMETRY to 3)
            layer.outsideArea shouldBe 0
            drawing().layers.map { it.kind }.toSet() shouldBe setOf(DesignLayerKind.POINTS, DesignLayerKind.LINES)
        }
    }

    @Test
    fun `S048 RF-11 un objeto fuera del ambito se ignora entero con su motivo`() {
        session("sistemas-mixtos.gpkg").use { session ->
            val source = CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 28)

            val summary = imported(
                import(
                    session,
                    choice(session, "canarias_utm28", source),
                    choice(session, "utm29", CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)),
                ),
            ).summary

            summary.layers[0].imported shouldBe 0
            summary.layers[0].ignored shouldBe mapOf(GpkgIgnoredReason.OUTSIDE_CRS_AREA to 3)
            summary.layers[1].imported shouldBe 3
            drawing().layers.map { it.sourceTable } shouldBe listOf("utm29")
        }
    }

    @Test
    fun `S048 RF-8 cada capa se reproyecta con su propio sistema`() {
        session("sistemas-mixtos.gpkg").use { session ->
            val choices = listOf(
                choice(session, "utm29", CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)),
                choice(session, "utm31", CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)),
                choice(session, "ed50_utm30", CadSourceCrs.Utm(GeodeticDatum.ED50, 30)),
                choice(session, "geograficas_4258", CadSourceCrs.Geographic(GeodeticDatum.ETRS89)),
            )

            imported(import(session, *choices.toTypedArray()))

            for (table in listOf("utm29", "utm31", "ed50_utm30", "geograficas_4258")) {
                assertSameElements(entities(layerOf(table, DesignLayerKind.POINTS).id), expectedElements("sistemas-mixtos.gpkg", table))
            }
        }
    }

    @Test
    fun `S048 RF-25 el resumen dice las tablas dejadas fuera y las capas en WGS 84`() {
        session("con-ajenas-25830.gpkg").use { session ->
            val summary = imported(import(session, choice(session, "puntos"))).summary

            summary.leftOutTables shouldBe listOf("censo", "teselas", "notas")
            summary.wgs84Layers shouldBe emptyList()
        }
        session("wgs84-4326.gpkg").use { session ->
            val wgs84 = choice(session, "puntos", CadSourceCrs.Geographic(GeodeticDatum.ETRS89)).copy(wgs84 = true)

            imported(import(session, wgs84)).summary.wgs84Layers shouldBe listOf("puntos")
        }
    }

    @Test
    fun `S048 RF-20 los atributos de cada objeto se guardan con su trozo`() {
        session("puntos-25830.gpkg").use { session ->
            val summary = imported(import(session, choice(session, "vertices"))).summary

            summary.layers.single().omittedAttributes shouldBe 1
            val blocks = db.openHelper.readableDatabase.query("SELECT data FROM cad_chunk_attributes").use { cursor ->
                buildList { while (cursor.moveToNext()) add(GpkgAttributesCodec.decode(cursor.getBlob(0))) }
            }
            blocks.size shouldBe 1
            val block = blocks.single()
            block.columns shouldBe listOf("nombre", "cota", "codigo", "fecha", "activo", "foto")
            block.rowCount shouldBe 5
            val chunk = runBlocking { repository.chunksIn(listOf(layerOf("vertices", DesignLayerKind.POINTS).id), everywhere) }.single()
            val byName = chunk.entities.indices.associate { chunk.entities[it].name to block.row(it) }
            byName.getValue("Esquina norte") shouldBe linkedMapOf(
                "nombre" to GpkgValue.Text("Esquina norte"),
                "cota" to GpkgValue.Real(646.377),
                "codigo" to GpkgValue.Integer(1),
                "fecha" to GpkgValue.Date("2026-10-01"),
                "activo" to GpkgValue.Bool(true),
                "foto" to GpkgValue.Null,
            )
            byName.getValue("Farola")["foto"] shouldBe GpkgValue.Omitted(70_000)
        }
    }

    @Test
    fun `S048 RF-20 todos los elementos de un poligono comparten sus atributos`() {
        session("poligonos-25830.gpkg").use { session ->
            import(session, choice(session, "parcelas"))

            val chunk = runBlocking { repository.chunksIn(listOf(layerOf("parcelas", DesignLayerKind.LINES).id), everywhere) }
            val rows = db.openHelper.readableDatabase.query("SELECT chunk_id, data FROM cad_chunk_attributes").use { cursor ->
                buildMap { while (cursor.moveToNext()) put(cursor.getLong(0), GpkgAttributesCodec.decode(cursor.getBlob(1))) }
            }

            val names = chunk.flatMap { c ->
                c.entities.indices.map { i -> c.entities[i].name to rows.getValue(c.chunkId).row(i)["nombre"] }
            }
            names.size shouldBe 4
            names.all { (name, attribute) -> attribute == GpkgValue.Text(name!!) } shouldBe true
        }
    }

    @Test
    fun `S048 RF-29 el progreso llega al total de objetos`() {
        session("poligonos-25830.gpkg").use { session ->
            val seen = mutableListOf<Pair<Long, Long>>()

            runBlocking {
                importer().import(
                    projectId,
                    crs,
                    "capas.gpkg",
                    GpkgImportOptions(listOf(choice(session, "parcelas"), choice(session, "manzanas"))),
                    session,
                ) {
                        d,
                        t,
                    ->
                    seen += d to t
                }
            }

            seen.first() shouldBe (0L to 4L)
            seen.last() shouldBe (4L to 4L)
            seen.map { it.first } shouldBe seen.map { it.first }.sorted()
        }
    }

    @Test
    fun `S048 RF-29 cada importacion tiene su propio dibujo y se pueden hacer varias`() {
        session("puntos-25830.gpkg").use { session ->
            import(session, choice(session, "vertices"))
            import(session, choice(session, "vertices"))

            runBlocking { repository.drawings(projectId).first() }.size shouldBe 2
        }
    }

    @Test
    fun `S048 RF-30 si no entra ningun objeto no se guarda nada`() {
        session("puntos-25830.gpkg").use { session ->
            val far = CentralPoint(37.3891, -5.9845) // Sevilla: ningún vértice de Madrid cae a 5 km

            val result = import(session, choice(session, "vertices"), area = ImportArea.Circle(far, 5_000.0))

            result.shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.NO_OBJECTS
            count("cad_drawings") shouldBe 0
            count("cad_layers") shouldBe 0
            count("cad_chunks") shouldBe 0
        }
    }

    @Test
    fun `S048 RF-16 pasar del limite de objetos no guarda nada`() {
        session("puntos-25830.gpkg").use { session ->
            val result = import(session, choice(session, "vertices"), importer = importer(maxObjects = 3))

            result.shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.TOO_MANY_OBJECTS
            listOf("cad_drawings", "cad_layers", "cad_chunks", "cad_chunk_attributes").forEach { count(it) shouldBe 0 }
        }
    }

    @Test
    fun `S048 RF-30 si falla la escritura no queda nada y lo dice`() {
        session("puntos-25830.gpkg").use { session ->
            val failing = object : CadRepository by repository {
                override suspend fun addChunks(chunks: List<CadChunkWrite>) = throw IllegalStateException("disco lleno")
            }

            val result = import(session, choice(session, "vertices"), importer = importer(failing))

            result.shouldBeInstanceOf<GpkgImportResult.Failed>().reason shouldBe GpkgImportFailure.WRITE_ERROR
            listOf("cad_drawings", "cad_layers", "cad_chunks", "cad_chunk_attributes").forEach { count(it) shouldBe 0 }
        }
    }

    @Test
    fun `S048 RF-30 cancelar no guarda nada del dibujo`() {
        session("puntos-25830.gpkg").use { session ->
            val cancelling = object : CadRepository by repository {
                override suspend fun addLayer(layer: es.topophone.core.model.cad.DesignLayer) {
                    repository.addLayer(layer)
                    throw CancellationException("cancelado por el usuario")
                }
            }

            val thrown = runCatching { import(session, choice(session, "vertices"), importer = importer(cancelling)) }

            (thrown.exceptionOrNull() is CancellationException) shouldBe true
            listOf("cad_drawings", "cad_layers", "cad_chunks", "cad_chunk_attributes").forEach { count(it) shouldBe 0 }
        }
    }

    @Test
    fun `S048 RF-30 cancelar la corrutina antes de leer tampoco deja el dibujo`() {
        session("puntos-25830.gpkg").use { session ->
            val result = runBlocking {
                val job =
                    async {
                        importer().import(projectId, crs, "capas.gpkg", GpkgImportOptions(listOf(choice(session, "vertices"))), session)
                    }
                job.cancel()
                runCatching { job.await() }
            }

            (result.isFailure || result.getOrNull() is GpkgImportResult.Imported) shouldBe true
            if (result.isFailure) {
                count("cad_drawings") shouldBe 0
            } else {
                count("cad_drawings") shouldBe 1
            }
        }
    }

    @Test
    fun `S048 RF-23 el color de cada capa sale de la paleta fija`() {
        session("sistemas-mixtos.gpkg").use { session ->
            import(
                session,
                choice(session, "utm29", CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)),
                choice(session, "utm31", CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)),
            )

            val colors = drawing().layers.map { it.color.argb and 0xFFFFFF }

            colors shouldBe GeoPackageImporter.PALETTE.take(2)
            GeoPackageImporter.PALETTE.size shouldBe 12
            GeoPackageImporter.PALETTE.toSet().size shouldBe 12
            drawing().layers.all { it.visible } shouldBe true
        }
    }

    @Test
    fun `S048 RF-26 el recuadro del dibujo es el de lo importado`() {
        session("puntos-25830.gpkg").use { session ->
            import(session, choice(session, "vertices"))

            val extent = drawing().drawing.extent.shouldNotBeNull()
            val points = expectedElements("puntos-25830.gpkg", "vertices").flatten()

            (abs(extent.minEasting - points.minOf { it.first }) < 1e-3) shouldBe true
            (abs(extent.maxNorthing - points.maxOf { it.second }) < 1e-3) shouldBe true
        }
    }
}

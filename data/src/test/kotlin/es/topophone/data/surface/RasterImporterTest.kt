package es.topophone.data.surface

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.raster.ByteArrayRasterSource
import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.formats.raster.RasterCrsResolver
import es.topophone.core.formats.raster.RasterReader
import es.topophone.core.formats.raster.RasterScan
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.SurfaceKind
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un ráster de cotas: copiar, escanear, leer por bandas, escribir, cancelar y fallar sin dejar nada (S045/RF-3 a RF-16). */
@RunWith(AndroidJUnit4::class)
class RasterImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomSurfaceRepository(db)

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @After
    fun close() = db.close()

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private fun fixture(name: String): ByteArray = File(root, "fixtures/raster/$name").readBytes()

    private fun table(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/raster/$name").readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val head = lines.first().split(",")
        return lines.drop(1).map { line -> head.zip(line.split(",")).toMap() }
    }

    private var ids = 0

    private fun importer(repo: SurfaceRepository = repository, maxBytes: Long = 250L * 1024 * 1024) = RasterImporter(
        repo,
        folder.newFolder().also {
            it.mkdirs()
        },
        clock = { 123_456L },
        newId = { "id-${ids++}" },
        maxFileBytes = maxBytes,
    )

    private fun stage(importer: RasterImporter, name: String, onProgress: (Long) -> Unit = {}): StagedRaster = runBlocking {
        importer.stage({ ByteArrayInputStream(fixture(name)) }, onProgress).let {
            it.shouldBeInstanceOf<RasterStageResult.Staged>().staged
        }
    }

    private fun sourceOf(name: String): CadSourceCrs {
        val header = (RasterReader.scan(ByteArrayRasterSource(fixture(name))) as RasterScan.Found).header
        return (RasterCrsResolver.resolve(header.epsg) as? RasterCrsDeclaration.Supported)?.source ?: CadSourceCrs.Project
    }

    private fun unitOf(name: String): LinearUnit = if (table("ficheros.csv").first { it["fichero"] == name }["unidad"] == "ft") {
        LinearUnit.INTERNATIONAL_FOOT
    } else {
        LinearUnit.METRE
    }

    private fun import(
        importer: RasterImporter,
        staged: StagedRaster,
        name: String,
        source: CadSourceCrs = sourceOf(name),
        unit: LinearUnit = unitOf(name),
        heightSystem: HeightSystem = HeightSystem.ORTHOMETRIC,
        crs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): RasterImportResult = runBlocking {
        importer.import(project, crs, staged, name, RasterImportOptions(source, unit, heightSystem), progress)
    }

    private fun imported(result: RasterImportResult) = result.shouldBeInstanceOf<RasterImportResult.Imported>()

    private fun count(table: String): Int = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
        it.moveToFirst()
        it.getInt(0)
    }

    @Test
    fun `S045 RF-13 un GeoTIFF se copia se escanea y se importa como superficie activa`() = runBlocking<Unit> {
        val importer = importer()
        val staged = stage(importer, "f32-sin-tiras-area.tif")
        val header = importer.scan(staged).shouldBeInstanceOf<RasterScanResult.Found>().header

        header.columns shouldBe 40
        header.rows shouldBe 36

        val result = imported(import(importer, staged, "f32-sin-tiras-area.tif"))

        result.summary.activated shouldBe true
        result.summary.summary.dataCells shouldBe 1436
        result.summary.summary.noDataCells shouldBe 4
        result.summary.summary.minElevationM shouldBe 600.0
        result.summary.summary.maxElevationM shouldBe 647.0079956054688
        val listed = repository.surfaces(project).first().single()
        listed.kind shouldBe SurfaceKind.RASTER
        listed.name shouldBe "f32-sin-tiras-area"
        listed.fileName shouldBe "f32-sin-tiras-area.tif"
        listed.importedAtMs shouldBe 123_456L
        listed.raster!!.valueType shouldBe RasterValueType.FLOAT32
        listed.raster!!.heightSystem shouldBe HeightSystem.ORTHOMETRIC
        repository.activeSurfaceId(project).first() shouldBe listed.id
        staged.close()
    }

    @Test
    fun `S045 RF-14 cada celda guardada es la del fichero sin redondear en todos los formatos`() = runBlocking<Unit> {
        val expected = table("celdas.csv").groupBy { it.getValue("fichero") }
        for (row in table("ficheros.csv").filter { it["resultado"] == "OK" }) {
            val name = row.getValue("fichero")
            val importer = importer()
            val staged = stage(importer, name)
            val result = imported(import(importer, staged, name))

            result.summary.summary.dataCells shouldBe row.getValue("con_dato").toLong()
            result.summary.summary.noDataCells shouldBe row.getValue("sin_dato_celdas").toLong()
            val raster = repository.surfaces(project).first().first { it.id == result.surfaceId }.raster!!
            raster.valueType shouldBe if (row.getValue("tipo") == "f64") RasterValueType.FLOAT64 else RasterValueType.FLOAT32
            val tile = repository.rasterTile(result.surfaceId, 0, 0)!!
            if (name !in setOf("f32-utm29.tif", "f32-geograficas.tif", "f32-sin-geoide.tif")) {
                for (cell in expected.getValue(name)) {
                    val value = tile.valueAt(cell.getValue("columna").toInt(), cell.getValue("fila").toInt())
                    if (cell.getValue("valor") ==
                        "nodata"
                    ) {
                        value.isNaN() shouldBe true
                    } else {
                        value shouldBe cell.getValue("valor").toDouble()
                    }
                }
            }
            staged.close()
            repository.delete(result.surfaceId)
        }
    }

    @Test
    fun `S045 RF-14 un raster de mas de 128 columnas guarda un bloque por cada 128`() = runBlocking<Unit> {
        val importer = importer()
        val text = "ncols 300\nnrows 130\nxllcorner 440000\nyllcorner 4473000\ncellsize 10\nNODATA_value -9999\n" +
            (0 until 130).joinToString("\n") { row ->
                (0 until 300).joinToString(" ") { col -> (600 + col * 0.01 + row * 0.02).toString() }
            } +
            "\n"
        val staged = runBlocking {
            importer.stage({ ByteArrayInputStream(text.toByteArray()) }) {}.shouldBeInstanceOf<RasterStageResult.Staged>().staged
        }

        val result = imported(import(importer, staged, "ancho.asc", source = CadSourceCrs.Project, unit = LinearUnit.METRE))

        db.surfaces().rasterTileCount(result.surfaceId) shouldBe 6 // 3 columnas de bloques por 2 filas de bloques
        for ((col, row) in listOf(0 to 0, 1 to 0, 2 to 0, 0 to 1, 1 to 1, 2 to 1)) {
            (repository.rasterTile(result.surfaceId, col, row) != null) shouldBe true
        }
        repository.rasterTile(result.surfaceId, 3, 0).shouldBeNull()
        repository.rasterTile(result.surfaceId, 0, 2).shouldBeNull()
        repository.rasterTile(result.surfaceId, 2, 1)!!.valueAt(43, 1) shouldBe (600 + 299 * 0.01 + 129 * 0.02 plusOrMinus 1e-9)
        repository.rasterTile(result.surfaceId, 2, 1)!!.valueAt(44, 1).isNaN() shouldBe true
        staged.close()
    }

    @Test
    fun `S045 RF-16 la extension sale en el sistema del proyecto y la cota en metros`() = runBlocking<Unit> {
        val importer = importer()
        val feetStaged = stage(importer, "f32-pies.tif")
        val feet = imported(import(importer, feetStaged, "f32-pies.tif"))
        val utm29Staged = stage(importer, "f32-utm29.tif")
        val utm29 = imported(import(importer, utm29Staged, "f32-utm29.tif"))

        feet.summary.summary.minElevationM shouldBe (600.0 * 0.3048 plusOrMinus 1e-9)
        val expected = table("extensiones.csv").first { it["fichero"] == "f32-utm29.tif" }
        utm29.summary.summary.extent.minEasting shouldBe (expected.getValue("min_este").toDouble() plusOrMinus 1e-3)
        utm29.summary.summary.extent.maxNorthing shouldBe (expected.getValue("max_norte").toDouble() plusOrMinus 1e-3)
    }

    @Test
    fun `S045 RF-10 el sistema de alturas elegido es el que se guarda`() = runBlocking<Unit> {
        val importer = importer()
        val staged = stage(importer, "f32-sin-tiras-area.tif")
        val result = imported(import(importer, staged, "f32-sin-tiras-area.tif", heightSystem = HeightSystem.ELLIPSOIDAL))

        repository.surfaces(project).first().first { it.id == result.surfaceId }.raster!!.heightSystem shouldBe HeightSystem.ELLIPSOIDAL
    }

    @Test
    fun `S045 RF-3 y RF-4 cada fichero rechazado dice su motivo al escanear`() = runBlocking<Unit> {
        val expected = mapOf(
            "bigtiff.tif" to RasterImportFailure.BIGTIFF,
            "girado.tif" to RasterImportFailure.ROTATED,
            "color-rgb.tif" to RasterImportFailure.COLOUR,
            "compresion-jpeg.tif" to RasterImportFailure.UNSUPPORTED_COMPRESSION,
            "sin-georreferencia.tif" to RasterImportFailure.NOT_GEOREFERENCED,
            "no-es-raster.txt" to RasterImportFailure.NOT_A_RASTER,
            "vacio.tif" to RasterImportFailure.EMPTY,
        )
        for ((name, reason) in expected) {
            val importer = importer()
            val staged = stage(importer, name)

            importer.scan(staged) shouldBe RasterScanResult.Failed(reason)
            staged.close()
        }
    }

    @Test
    fun `S045 RF-3 un raster sin ninguna celda con dato no se guarda`() = runBlocking<Unit> {
        val importer = importer()
        val staged = stage(importer, "todo-sin-dato.tif")
        importer.scan(staged).shouldBeInstanceOf<RasterScanResult.Found>()

        import(importer, staged, "todo-sin-dato.tif") shouldBe RasterImportResult.Failed(RasterImportFailure.NO_DATA_CELLS)

        count("surfaces") shouldBe 0
        count("raster_tiles") shouldBe 0
    }

    @Test
    fun `S045 RF-3 un raster fuera del ambito del sistema del proyecto no se guarda`() = runBlocking<Unit> {
        val importer = importer()
        val staged = stage(importer, "f32-sin-tiras-area.tif")

        val result = import(importer, staged, "f32-sin-tiras-area.tif", source = CadSourceCrs.Geographic(GeodeticDatum.ETRS89))

        result shouldBe RasterImportResult.Failed(RasterImportFailure.OUTSIDE_CRS_AREA)
        count("surfaces") shouldBe 0
    }

    @Test
    fun `S045 RF-5 un fichero que pasa del limite no se copia entero`() = runBlocking<Unit> {
        val limited = importer(maxBytes = 1_000L)

        val result = runBlocking { limited.stage({ ByteArrayInputStream(ByteArray(5_000)) }) {} }

        result shouldBe RasterStageResult.Failed(RasterImportFailure.TOO_LARGE)
        folderIsEmpty(limited) shouldBe true
    }

    @Test
    fun `S045 RF-13 un fallo al leer el fichero no deja ni el temporal`() = runBlocking<Unit> {
        val importer = importer()
        val broken = object : InputStream() {
            private var sent = 0

            override fun read(): Int {
                if (sent >= 100) throw IOException("se cortó")
                sent++
                return 7
            }
        }

        val result = runBlocking { importer.stage({ broken }) {} }

        result shouldBe RasterStageResult.Failed(RasterImportFailure.READ_ERROR)
        folderIsEmpty(importer) shouldBe true
    }

    @Test
    fun `S045 RF-13 cerrar el temporal lo borra`() = runBlocking<Unit> {
        val importer = importer()
        val staged = stage(importer, "esquina.asc")
        staged.file.exists() shouldBe true

        staged.close()

        staged.file.exists() shouldBe false
    }

    @Test
    fun `S045 RF-13 cancelar la importacion no deja nada guardado`() = runBlocking<Unit> {
        val cancelling = object : SurfaceRepository by repository {
            override suspend fun addRasterTiles(surfaceId: String, tiles: List<RasterTileWrite>) {
                repository.addRasterTiles(surfaceId, tiles)
                throw CancellationException("cancelado")
            }
        }
        val importer = importer(cancelling)
        val staged = stage(importer, "f32-sin-tiras-area.tif")

        val cancelled = try {
            import(importer, staged, "f32-sin-tiras-area.tif")
            false
        } catch (e: CancellationException) {
            true
        }

        cancelled shouldBe true
        count("surfaces") shouldBe 0
        count("raster_tiles") shouldBe 0
    }

    @Test
    fun `S045 RF-13 si no se puede escribir no se guarda nada`() = runBlocking<Unit> {
        val failing = object : SurfaceRepository by repository {
            override suspend fun addRasterTiles(surfaceId: String, tiles: List<RasterTileWrite>) {
                repository.addRasterTiles(surfaceId, tiles)
                throw IOException("disco lleno")
            }
        }
        val importer = importer(failing)
        val staged = stage(importer, "f32-sin-tiras-area.tif")

        import(importer, staged, "f32-sin-tiras-area.tif") shouldBe RasterImportResult.Failed(RasterImportFailure.WRITE_ERROR)

        count("surfaces") shouldBe 0
        count("raster_tiles") shouldBe 0
        staged.file.exists() shouldBe true // el temporal sigue para reintentar tras liberar espacio
    }

    @Test
    fun `S045 RF-13 un fichero cortado por la mitad falla al leer y no se guarda`() = runBlocking<Unit> {
        val importer = importer()
        val bytes = fixture("esquina.asc")
        val staged = runBlocking {
            importer.stage({ ByteArrayInputStream(bytes.copyOf(bytes.size / 2)) }) {}.shouldBeInstanceOf<RasterStageResult.Staged>().staged
        }
        importer.scan(staged).shouldBeInstanceOf<RasterScanResult.Found>()

        import(importer, staged, "esquina.asc") shouldBe RasterImportResult.Failed(RasterImportFailure.READ_ERROR)

        count("surfaces") shouldBe 0
    }

    @Test
    fun `S045 RF-13 el progreso de la importacion sube hasta el total`() = runBlocking<Unit> {
        val importer = importer()
        val staged = stage(importer, "f32-sin-tiras-area.tif")
        val seen = ArrayList<Pair<Long, Long>>()

        imported(import(importer, staged, "f32-sin-tiras-area.tif", progress = { done, total -> seen += done to total }))

        (seen.isNotEmpty()) shouldBe true
        seen.last().first shouldBe seen.last().second
        seen.map { it.first } shouldBe seen.map { it.first }.sorted()
    }

    @Test
    fun `S045 RF-13 el progreso de la copia avisa de los bytes copiados`() = runBlocking<Unit> {
        val importer = importer()
        val seen = ArrayList<Long>()

        stage(importer, "esquina.asc") { seen += it }

        seen.last() shouldBe fixture("esquina.asc").size.toLong()
    }

    @Test
    fun `S045 RF-17 el segundo raster no se activa solo`() = runBlocking<Unit> {
        val importer = importer()
        val first = imported(import(importer, stage(importer, "esquina.asc"), "esquina.asc"))
        val second = imported(import(importer, stage(importer, "centro.asc"), "centro.asc"))

        first.summary.activated shouldBe true
        second.summary.activated shouldBe false
        repository.activeSurfaceId(project).first() shouldBe first.surfaceId
    }

    private fun folderIsEmpty(importer: RasterImporter): Boolean = importer.tempDirectory.listFiles().orEmpty().isEmpty()
}

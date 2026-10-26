package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.cad.CadChunkCodec
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadDrawing
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.backup.BackupFixture.Companion.columns
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.cad.CadChunkWrite
import es.topophone.data.cad.RoomCadRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Los dibujos viajan en las copias y los paquetes con sus capas, su visibilidad y sus trozos (S040/RF-31), y los puntos replanteados
 * de un dibujo con su origen, su modo, su PK y su error lateral aunque el dibujo ya no esté (S041/RF-32).
 */
@RunWith(AndroidJUnit4::class)
class CadBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "cad-a-${System.nanoTime()}.db"
    private val theirs = "cad-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var cad: RoomCadRepository
    private lateinit var project: String
    private var counter = 0

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.13.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.13.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        cad = RoomCadRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con dibujo")) as SaveResult.Saved).id
        drawing("d1", "uno.dxf")
        drawing("d2", "dos.dxf")
        // Un dibujo a medias, que no debe viajar.
        cad.begin(dto("d3", "a-medias.dxf"))
        cad.addLayer(layer("l-d3", "d3", "Fantasma"))
        cad.addChunks(listOf(chunk("l-d3", line(1.0, 2.0))))
        Unit
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private fun dto(id: String, file: String) = CadDrawing(
        id,
        project,
        file,
        1_792_346_000_000L + counter++,
        CadSourceCrs.Utm(GeodeticDatum.ED50, 30),
        LinearUnit.US_SURVEY_FOOT,
        useElevations = true,
        hasElevations = false,
    )

    private fun layer(id: String, drawingId: String, name: String, kind: DesignLayerKind = DesignLayerKind.LINES) =
        DesignLayer(id, drawingId, name, kind, CadColor.rgb(0xFF0000))

    private fun line(e: Double, n: Double) = CadEntity(
        CadEntityKind.LINE,
        doubleArrayOf(e, e + 10.123456789012),
        doubleArrayOf(n, n),
        doubleArrayOf(1.5, 2.5),
        null,
    )

    private fun chunk(layerId: String, entity: CadEntity): CadChunkWrite {
        val box = entity.bounds
        return CadChunkWrite(layerId, box, 1, CadChunkCodec.encode(listOf(entity)))
    }

    private suspend fun drawing(id: String, file: String) {
        cad.begin(dto(id, file))
        cad.addLayer(layer("l1-$id", id, "Ejes"))
        cad.addLayer(
            layer("l2-$id", id, "Pozos", DesignLayerKind.POINTS).copy(visible = false, position = 1, color = CadColor.AUTO_CONTRAST),
        )
        cad.addChunks(listOf(chunk("l1-$id", line(440000.123456789012, 4474000.987654321098)), chunk("l2-$id", line(440100.0, 4474100.0))))
        cad.finish(
            id,
            mapOf("l1-$id" to 1, "l2-$id" to 1),
            hasElevations = true,
            incomplete = false,
            extent = CadExtent(1.0, 2.0, 3.0, 4.0),
        )
    }

    private fun file(name: String): File = context.getDatabasePath(name)

    /** Las filas de [table] del proyecto de los dibujos, sin las columnas de ids de [ignore]. */
    private fun cadRows(db: String, table: String, ignore: Set<String> = emptySet()): List<List<Any?>> {
        val cols = columns(file(db), table)
        val keep = cols.indices.filter { cols[it] !in ignore }
        return rows(file(db), table).map { row -> keep.map { row[it] } }
    }

    private fun count(db: String, table: String) = rows(file(db), table).size

    @Test
    fun `S040 RF-31 el paquete lleva los dibujos terminados`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()

        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }.shouldBeInstanceOf<ImportResult.Imported>()

        count(theirs, "cad_drawings") shouldBe 2
        count(theirs, "cad_layers") shouldBe 4
        count(theirs, "cad_chunks") shouldBe 4
        val cols = columns(file(theirs), "cad_drawings")
        rows(file(theirs), "cad_drawings").map { it[cols.indexOf("file_name")] }.toSet() shouldBe setOf("uno.dxf", "dos.dxf")
        rows(file(theirs), "cad_drawings").all { it[cols.indexOf("status")] == "READY" } shouldBe true
    }

    @Test
    fun `S040 RF-31 el paquete conserva capas, visibilidad y trozos`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()

        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }

        val drawings = runBlocking { RoomCadRepository(other).drawings(project).first() }
        drawings.map { it.drawing.fileName } shouldBe listOf("uno.dxf", "dos.dxf")
        drawings.first().drawing.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        drawings.first().drawing.unit shouldBe LinearUnit.US_SURVEY_FOOT
        drawings.first().drawing.extent shouldBe CadExtent(1.0, 2.0, 3.0, 4.0)
        drawings.first().layers.map { it.name to it.visible } shouldBe listOf("Ejes" to true, "Pozos" to false)
        drawings.first().layers[1].color shouldBe CadColor.AUTO_CONTRAST
        val chunks = runBlocking {
            RoomCadRepository(other).chunksIn(drawings.first().layers.map { it.id }, CadExtent(-1.0e12, 1.0e12, -1.0e12, 1.0e12))
        }
        chunks.flatMap { it.entities }.map { it.easting[0] }.sorted() shouldBe listOf(440000.123456789012, 440100.0)
        // Los trozos son los mismos bytes que en el móvil de origen.
        val original = cadRows(mine, "cad_chunks", ignore = setOf("id", "layer_id")).map { it.last() }.toSet()
        val copied = cadRows(theirs, "cad_chunks", ignore = setOf("id", "layer_id")).map { it.last() }.toSet()
        copied.size shouldBe 2
        original.containsAll(copied) shouldBe true
    }

    @Test
    fun `S040 RF-31 importar el paquete dos veces da ids nuevos`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }
        val second = runBlocking { otherRepository.importPackage(valid, ImportChoice.AS_NEW, importedLabel = "importado") }

        second.shouldBeInstanceOf<ImportResult.Imported>()
        count(theirs, "cad_drawings") shouldBe 4
        count(theirs, "cad_layers") shouldBe 8
        count(theirs, "cad_chunks") shouldBe 8
    }

    @Test
    fun `S040 RF-31 sustituir deja el dibujo del paquete`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()
        runBlocking { cad.setLayerVisible("l1-d1", false) }
        val valid = runBlocking { repository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()

        runBlocking {
            repository.importPackage(valid, ImportChoice.REPLACE, importedLabel = "importado")
        }.shouldBeInstanceOf<ImportResult.Imported>()

        // El del paquete se queda con su id y con la capa visible que tenía al hacerlo.
        val inPlace = runBlocking { cad.drawings(project).first() }
        inPlace.map { it.drawing.id } shouldBe listOf("d1", "d2")
        inPlace.first().layers.first().visible shouldBe true
        count(mine, "cad_drawings") shouldBe 4 // los 2 del paquete y los 2 de la copia del móvil en la papelera
    }

    @Test
    fun `S040 RF-31 la copia completa restaura los dibujos`() {
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { cad.deleteDrawing("d1") }
        runBlocking { cad.setLayerVisible("l1-d2", false) }
        runBlocking { cad.drawings(project).first() }.map { it.drawing.id } shouldBe listOf("d2")

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        val back = runBlocking { cad.drawings(project).first() }
        back.map { it.drawing.id } shouldBe listOf("d1", "d2")
        back.last().layers.map { it.visible } shouldBe listOf(true, false)
        back.first().layers.map { it.visible } shouldBe listOf(true, false)
    }

    @Test
    fun `S040 RNF-T6 borrar el proyecto borra sus dibujos`() {
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()
        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }

        other.openHelper.writableDatabase.execSQL("DELETE FROM projects WHERE id = ?", arrayOf<Any>(project))

        count(theirs, "cad_drawings") shouldBe 0
        count(theirs, "cad_layers") shouldBe 0
        count(theirs, "cad_chunks") shouldBe 0
    }

    /** Un punto replanteado de un elemento del dibujo d1, con su modo, su PK, su desplazamiento y su error lateral (S041/RF-28). */
    private fun stakedFromDrawing(): Point {
        val base = fixture.measured("Eje_1_stkd", project)
        val ref = StakeoutRef(
            designId = "cad:l1-d1:1:0",
            designName = "Ejes",
            design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.4), 440_000.123456789012, 4_474_000.987654321098, 648.19),
            dEastM = -0.004,
            dNorthM = 0.012,
            dHorizontalM = 0.0126,
            dHeightM = -0.021,
            designElevationM = 648.19,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = true,
            source = StakeSource.CadElement("uno.dxf", "Ejes", CadEntityKind.POLYLINE),
            mode = LineStakeoutMode.ALONG,
            chainageM = 45.123456789,
            lateralOffsetM = -1.5,
            dLateralM = 0.012345678,
        )
        return base.copy(origin = PointOrigin.STAKED, staked = ref)
    }

    private fun stakeRow(db: String): Map<String, Any?> {
        val cols = columns(file(db), "points")
        val row = rows(file(db), "points").first { it[cols.indexOf("name")] == "Eje_1_stkd" }
        return cols.zip(row).toMap()
    }

    @Test
    fun `S041 RF-32 el paquete lleva el punto replanteado de un dibujo con su origen, su modo, su PK y su error lateral`() {
        runBlocking { fixture.points.create(stakedFromDrawing()) }
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()

        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }

        val row = stakeRow(theirs)
        row["stake_cad_drawing"] shouldBe "uno.dxf"
        row["stake_cad_layer"] shouldBe "Ejes"
        row["stake_cad_kind"] shouldBe "POLYLINE"
        row["stake_mode"] shouldBe "ALONG"
        row["stake_chainage"] shouldBe 45.123456789
        row["stake_offset"] shouldBe -1.5
        row["stake_d_lateral"] shouldBe 0.012345678
    }

    @Test
    fun `S041 RF-32 la copia completa restaura el replanteo de un dibujo aunque el dibujo se haya borrado despues`() {
        runBlocking { fixture.points.create(stakedFromDrawing()) }
        val backup = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()
        runBlocking { cad.deleteDrawing("d1") }
        // Borrar el dibujo no toca el punto ni lo que guarda de su origen
        val afterDelete = runBlocking { RoomPointRepository(db).point("id-Eje_1_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()
        afterDelete.source shouldBe StakeSource.CadElement("uno.dxf", "Ejes", CadEntityKind.POLYLINE)

        val valid = runBlocking { repository.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()

        val restored = runBlocking { RoomPointRepository(db).point("id-Eje_1_stkd").first() }.shouldNotBeNull().staked.shouldNotBeNull()
        restored shouldBe stakedFromDrawing().staked
        runBlocking { cad.drawings(project).first() }.map { it.drawing.id } shouldBe listOf("d1", "d2")
    }

    @Test
    fun `S041 RF-32 un paquete de un proyecto cuyo dibujo ya no esta conserva el punto replanteado con lo que guardo`() {
        runBlocking { fixture.points.create(stakedFromDrawing()) }
        runBlocking { cad.deleteDrawing("d1") }
        val pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(project, it) } shouldBe true }.toByteArray()

        val valid = runBlocking { otherRepository.read({ pkg.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { otherRepository.importPackage(valid, null, importedLabel = "importado") }

        stakeRow(theirs)["stake_cad_drawing"] shouldBe "uno.dxf"
        count(theirs, "cad_drawings") shouldBe 1
    }
}

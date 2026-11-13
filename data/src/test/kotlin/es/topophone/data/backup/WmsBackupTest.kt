package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.backup.BackupManifest
import es.topophone.core.formats.wms.WmsCapabilities
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.ExportedSchemas
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import es.topophone.data.wms.AddLayerResult
import es.topophone.data.wms.RoomProjectLayersRepository
import es.topophone.data.wms.RoomWmsLibraryRepository
import es.topophone.data.wms.WmsAddResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** La biblioteca de servicios WMS y las capas de los proyectos viajan en la copia y en el paquete (S036/RF-25, D1 del plan). */
@RunWith(AndroidJUnit4::class)
class WmsBackupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "wms-backup-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var library: RoomWmsLibraryRepository
    private lateinit var layers: RoomProjectLayersRepository
    private lateinit var fixture: BackupFixture
    private lateinit var project: String
    private lateinit var serviceA: String
    private lateinit var serviceB: String

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.36.0", workDir = folder.newFolder("w"), undoDir = folder.newFolder("u"))
        library = RoomWmsLibraryRepository(db)
        layers = RoomProjectLayersRepository(db)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con WMS")) as SaveResult.Saved).id
        serviceA = service("https://a.es/wms", "Servicio A", "capa-1", "capa-2", "capa-3")
        serviceB = service("https://b.es/wms", "Servicio B", "otra-1")
        layers.add(project, serviceA, "capa-1").shouldBeInstanceOf<AddLayerResult.Added>()
        val second = layers.add(project, serviceA, "capa-2").shouldBeInstanceOf<AddLayerResult.Added>().layer
        layers.setOpacity(second.id, 40)
        layers.setVisible(second.id, false)
        Unit
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun service(url: String, title: String, vararg names: String): String = runBlocking {
        val address = (WmsAddress.parse(url) as WmsAddressResult.Valid).address
        val infos = names.mapIndexed { i, n -> WmsLayerInfo(i, 0, n, "Capa $n", "", true, i == 0, "EPSG:3857") }
        val caps = WmsCapabilities("1.3.0", title, "© $title", listOf("image/png"), listOf("text/plain"), infos)
        library.add(address, caps, WmsRequestMode.TILES, name = title).shouldBeInstanceOf<WmsAddResult.Added>().service.id
    }

    private fun backup(): ByteArray = ByteArrayOutputStream().also {
        runBlocking { repository.writeBackup(it) } shouldBe true
    }.toByteArray()

    private fun restore(bytes: ByteArray) {
        val valid = runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { repository.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()
    }

    private fun source() = context.getDatabasePath(name)

    private fun snapshot(scope: SnapshotScope): File {
        val target = File(folder.root, "snapshot-${System.nanoTime()}.db")
        DatabaseSnapshot(context, source()).copy(target, scope)
        return target
    }

    private fun serviceIds() = runBlocking { library.services().first() }.map { it.id }.toSet()

    private fun projectLayers() =
        runBlocking { layers.layers(project).first() }.map { Triple(it.layer.layerName, it.layer.visible, it.layer.opacity) }

    @Test
    fun `S036 RF-25 la copia completa lleva la biblioteca entera y las capas de los proyectos`() {
        val target = snapshot(SnapshotScope.All)

        for (table in listOf("wms_services", "wms_layers", "project_wms_layers")) rows(target, table) shouldBe rows(source(), table)
        rows(target, "wms_services").size shouldBe 2
        rows(target, "wms_layers").size shouldBe 4
        rows(target, "project_wms_layers").size shouldBe 2
    }

    @Test
    fun `S036 RF-25 el paquete de un proyecto lleva solo los servicios que usa con sus capas leidas`() {
        val target = snapshot(SnapshotScope.Project(project))

        rows(target, "wms_services").map { it[0] } shouldBe listOf(serviceA)
        rows(target, "wms_layers").size shouldBe 3
        rows(target, "wms_layers").map { it[0] }.toSet() shouldBe setOf(serviceA)
        rows(target, "project_wms_layers").map { it[1] }.toSet() shouldBe setOf(project)
        rows(target, "project_wms_layers").size shouldBe 2
    }

    @Test
    fun `S036 RF-25 el paquete de un proyecto sin capas no lleva ningun servicio`() {
        val created = runBlocking { fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Sin WMS")) }
        val other = (created as SaveResult.Saved).id

        val target = snapshot(SnapshotScope.Project(other))

        rows(target, "wms_services") shouldBe emptyList()
        rows(target, "wms_layers") shouldBe emptyList()
        rows(target, "project_wms_layers") shouldBe emptyList()
    }

    @Test
    fun `S036 RF-25 restaurar una copia deja la biblioteca y las capas del proyecto como estaban en ella`() {
        val before = projectLayers()
        val bytes = backup()
        runBlocking {
            library.delete(serviceB)
            service("https://c.es/wms", "Servicio C", "z")
            layers.move(layers.layers(project).first().last().layer.id, 0)
        }
        serviceIds().contains(serviceB) shouldBe false

        restore(bytes)

        serviceIds() shouldBe setOf(serviceA, serviceB)
        projectLayers() shouldBe before
        runBlocking { library.layers(serviceA) }.map { it.name } shouldBe listOf("capa-1", "capa-2", "capa-3")
    }

    @Test
    fun `S036 RF-25 deshacer la restauracion devuelve la biblioteca que habia antes`() {
        val bytes = backup()
        val extra = service("https://c.es/wms", "Servicio C", "z")
        runBlocking { library.delete(serviceB) }

        restore(bytes)
        serviceIds().contains(extra) shouldBe false
        serviceIds().contains(serviceB) shouldBe true
        runBlocking { repository.undoRestore() }.shouldBeInstanceOf<RestoreResult.Restored>()

        serviceIds() shouldBe setOf(serviceA, extra)
    }

    @Test
    fun `S036 RF-25 la copia de una version anterior sin tablas WMS se restaura con la biblioteca vacia`() {
        val old = File(folder.root, "v31.db")
        ExportedSchemas.create(old, 31)
        val manifest = BackupManifest(BackupKind.BACKUP, 31, "0.31.0", Instant.EPOCH, 0, 0)
        val settingsFile = File(folder.root, "settings-31.json").apply { writeText("""{"stores":{},"appLanguage":""}""") }
        val files = mapOf(BackupArchive.DATABASE to old, BackupArchive.SETTINGS to settingsFile)
        val bytes = ByteArrayOutputStream().also { BackupArchive.write(manifest, files, it) }.toByteArray()

        restore(bytes)

        serviceIds() shouldBe emptySet()
        runBlocking { layers.layers(project).first() } shouldBe emptyList()
    }
}

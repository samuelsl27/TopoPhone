package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.formats.wms.WmsCapabilities
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.SaveResult
import es.topophone.data.wms.AddLayerResult
import es.topophone.data.wms.ProjectLayerView
import es.topophone.data.wms.RoomProjectLayersRepository
import es.topophone.data.wms.RoomWmsLibraryRepository
import es.topophone.data.wms.WmsAddResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Importar un paquete de proyecto con capas WMS: los servicios se comparan por su direccion y no se duplican (S036/RF-25, A7 del plan). */
@RunWith(AndroidJUnit4::class)
class WmsPackageImportTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "wms-pkg-a-${System.nanoTime()}.db"
    private val theirs = "wms-pkg-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var settings: TestSettings
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var library: RoomWmsLibraryRepository
    private lateinit var layers: RoomProjectLayersRepository
    private lateinit var otherLibrary: RoomWmsLibraryRepository
    private lateinit var otherLayers: RoomProjectLayersRepository
    private lateinit var project: String
    private lateinit var serviceA: String

    @Before
    fun open() = runBlocking {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        val fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        repository =
            BackupRepository(context, db, settings.archive, "0.36.0", workDir = folder.newFolder("wa"), undoDir = folder.newFolder("ua"))
        otherRepository =
            BackupRepository(context, other, settings.archive, "0.36.0", workDir = folder.newFolder("wb"), undoDir = folder.newFolder("ub"))
        library = RoomWmsLibraryRepository(db)
        layers = RoomProjectLayersRepository(db)
        otherLibrary = RoomWmsLibraryRepository(other)
        otherLayers = RoomProjectLayersRepository(other)
        project = (fixture.projects.create(ProjectDefaults().toDraft().copy(name = "Con WMS")) as SaveResult.Saved).id
        serviceA = service(library, "https://a.es/wms", "Servicio A", "capa-1", "capa-2", "capa-3")
        service(library, "https://b.es/wms", "Servicio B", "otra-1")
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
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private fun service(into: RoomWmsLibraryRepository, url: String, title: String, vararg names: String): String = runBlocking {
        val address = (WmsAddress.parse(url) as WmsAddressResult.Valid).address
        val infos = names.mapIndexed { i, n -> WmsLayerInfo(i, 0, n, "Capa $n", "", true, i == 0, "EPSG:3857") }
        val caps = WmsCapabilities("1.3.0", title, "© $title", listOf("image/png"), listOf("text/plain"), infos)
        into.add(address, caps, WmsRequestMode.TILES, name = title).shouldBeInstanceOf<WmsAddResult.Added>().service.id
    }

    private fun packageBytes() = ByteArrayOutputStream().also {
        runBlocking { repository.writePackage(project, it) } shouldBe true
    }.toByteArray()

    private fun importInto(bytes: ByteArray, choice: ImportChoice? = null): String {
        val valid = runBlocking { otherRepository.read({ bytes.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Valid>()
        return runBlocking { otherRepository.importPackage(valid, choice, importedLabel = "importado") }
            .shouldBeInstanceOf<ImportResult.Imported>().projectId
    }

    private fun count(database: TopoPhoneDatabase, table: String): Int =
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }

    private fun views(projectId: String): List<ProjectLayerView> = runBlocking { otherLayers.layers(projectId).first() }

    private fun summary(list: List<ProjectLayerView>) = list.map { Triple(it.layer.layerName, it.layer.visible, it.layer.opacity) }

    @Test
    fun `S036 RF-25 el paquete anade sus servicios y las capas del proyecto`() {
        val imported = importInto(packageBytes())

        count(other, "wms_services") shouldBe 1 // solo el que usa el proyecto
        count(other, "wms_layers") shouldBe 3
        val list = views(imported)
        summary(list) shouldBe listOf(Triple("capa-2", false, 40), Triple("capa-1", true, 100))
        list.map { it.service.address }.toSet() shouldBe setOf("https://a.es/wms")
        list.all { it.available } shouldBe true
        list.map { it.layer.position } shouldBe listOf(0, 1)
    }

    @Test
    fun `S036 RF-25 un servicio que el movil ya tiene no se duplica ni se toca`() {
        val own = service(otherLibrary, "HTTPS://A.es/wms", "Mi servicio", "capa-1", "otra")
        runBlocking { otherLibrary.rename(own, "Ortofoto de la obra") }

        val imported = importInto(packageBytes())

        count(other, "wms_services") shouldBe 1
        val list = views(imported)
        list.map { it.service.id }.toSet() shouldBe setOf(own)
        runBlocking { otherLibrary.service(own) }?.name shouldBe "Ortofoto de la obra"
        runBlocking { otherLibrary.layers(own) }.map { it.name } shouldBe listOf("capa-1", "otra")
    }

    @Test
    fun `S036 RF-27 una capa que el servicio del movil no ofrece no esta disponible`() {
        service(otherLibrary, "https://a.es/wms", "Mi servicio", "capa-1")

        val list = views(importInto(packageBytes()))

        list.map { it.layer.layerName to it.available } shouldBe listOf("capa-2" to false, "capa-1" to true)
    }

    @Test
    fun `S036 RF-25 importar como nuevo dos veces no duplica el servicio`() {
        val bytes = packageBytes()
        val first = importInto(bytes)

        val second = importInto(bytes, ImportChoice.AS_NEW)

        (first == second) shouldBe false
        count(other, "wms_services") shouldBe 1
        count(other, "wms_layers") shouldBe 3
        count(other, "project_wms_layers") shouldBe 4
        summary(views(first)) shouldBe summary(views(second))
        views(first).map { it.layer.id }.toSet().intersect(views(second).map { it.layer.id }.toSet()) shouldBe emptySet()
    }

    @Test
    fun `S036 RF-25 sustituir lleva las capas del movil a la papelera`() {
        val bytes = packageBytes()
        val first = importInto(bytes)
        runBlocking { otherLayers.layers(first).first().first().let { otherLayers.remove(it.layer.id) } }
        summary(views(first)) shouldBe listOf(Triple("capa-1", true, 100))

        val replaced = importInto(bytes, ImportChoice.REPLACE)

        replaced shouldBe first
        summary(views(replaced)) shouldBe listOf(Triple("capa-2", false, 40), Triple("capa-1", true, 100))
        count(other, "wms_services") shouldBe 1
        val trashed = other.openHelper.readableDatabase.query("SELECT id FROM projects WHERE deleted_at IS NOT NULL").use {
            it.moveToFirst() shouldBe true
            it.getString(0)
        }
        summary(views(trashed)) shouldBe listOf(Triple("capa-1", true, 100))
    }

    @Test
    fun `S036 RF-25 un proyecto sin capas no trae servicios`() {
        val created = runBlocking { BackupFixture(db).projects.create(ProjectDefaults().toDraft().copy(name = "Sin WMS")) }
        val plain = (created as SaveResult.Saved).id
        val bytes = ByteArrayOutputStream().also { runBlocking { repository.writePackage(plain, it) } shouldBe true }.toByteArray()

        importInto(bytes)

        count(other, "wms_services") shouldBe 0
        count(other, "wms_layers") shouldBe 0
        count(other, "project_wms_layers") shouldBe 0
    }
}

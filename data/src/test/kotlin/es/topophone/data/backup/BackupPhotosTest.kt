package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupArchive
import es.topophone.core.formats.backup.BackupKind
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.photo.PointPhotoEntity
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** La copia completa lleva las fotos, restaurar y deshacer las devuelven y un fallo las deja como estaban (S017b/RF-28). */
@RunWith(AndroidJUnit4::class)
class BackupPhotosTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "bkph-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var photosDir: File
    private lateinit var undoDir: File
    private lateinit var repository: BackupRepository

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"), TestSettings.FakeLanguage("es"))
        photosDir = File(folder.newFolder("files"), "photos").apply { mkdirs() }
        undoDir = File(folder.root, "undo")
        repository = BackupRepository(
            context,
            db,
            settings.archive,
            "0.17.0",
            clock = { 0 },
            workDir = folder.newFolder("work"),
            undoDir = undoDir,
            photosDir = photosDir,
        )
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun source() = context.getDatabasePath(name)

    private fun addPhoto(pointId: String, id: String, content: String) {
        File(photosDir, "$id.jpg").writeText(content)
        runBlocking { db.photos().insert(PointPhotoEntity(id, pointId, "$id.jpg", "CAMERA", 1, 2, content.length.toLong(), 640, 480)) }
    }

    private fun backupBytes() = ByteArrayOutputStream().also { runBlocking { repository.writeBackup(it) } shouldBe true }.toByteArray()

    private fun valid(bytes: ByteArray) =
        runBlocking { repository.read({ bytes.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()

    private fun photoFiles() = photosDir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() }

    private fun zipNames(bytes: ByteArray): List<String> = buildList {
        ZipInputStream(bytes.inputStream()).use { while (true) add((it.nextEntry ?: break).name) }
    }

    @Test
    fun `S017b RF-28 la copia lleva las fotos de todos los puntos, tambien los de la papelera`() {
        addPhoto("id-1", "f-uno", "uno")
        addPhoto("id-4", "f-papelera", "papelera") // el punto 4 esta en la papelera
        File(photosDir, "huerfana.jpg").writeText("sin fila") // un fichero sin fila no entra

        val names = zipNames(backupBytes())

        names.filter { it.startsWith("photos/") }.sorted() shouldBe listOf("photos/f-papelera.jpg", "photos/f-uno.jpg")
        valid(backupBytes()).photos.keys shouldBe setOf("f-uno", "f-papelera")
    }

    @Test
    fun `S017b RF-28 restaurar devuelve filas y ficheros de la copia`() {
        addPhoto("id-1", "f-uno", "uno")
        val backup = backupBytes()
        val photosAtBackup = rows(source(), "point_photos")
        // Despues de la copia: otra foto y se borra la primera.
        addPhoto("id-2", "f-dos", "dos")
        File(photosDir, "f-uno.jpg").delete()

        runBlocking { repository.restore(valid(backup)) }.shouldBeInstanceOf<RestoreResult.Restored>()

        rows(source(), "point_photos") shouldBe photosAtBackup
        photoFiles() shouldBe mapOf("f-uno.jpg" to "uno")
        File(photosDir.parentFile, "photos.old").exists() shouldBe false
        File(photosDir.parentFile, "photos.incoming").exists() shouldBe false
    }

    @Test
    fun `S017b RF-28 un fallo al confirmar deja las fotos como estaban`() {
        addPhoto("id-1", "f-uno", "uno")
        val backup = backupBytes()
        addPhoto("id-2", "f-dos", "dos")
        val rowsBefore = rows(source(), "point_photos")
        repository.hooks = RestoreHooks(beforeCommit = { error("sin espacio") })

        runBlocking { repository.restore(valid(backup)) } shouldBe RestoreResult.Failed

        photoFiles() shouldBe mapOf("f-uno.jpg" to "uno", "f-dos.jpg" to "dos")
        rows(source(), "point_photos") shouldBe rowsBefore
        File(photosDir.parentFile, "photos.old").exists() shouldBe false
    }

    @Test
    fun `S017b RF-28 un fallo en los ajustes tambien devuelve las fotos`() {
        addPhoto("id-1", "f-uno", "uno")
        val backup = backupBytes()
        addPhoto("id-2", "f-dos", "dos")
        repository.hooks = RestoreHooks(afterDatabase = { error("ajustes ilegibles") })

        runBlocking { repository.restore(valid(backup)) } shouldBe RestoreResult.Failed

        photoFiles() shouldBe mapOf("f-uno.jpg" to "uno", "f-dos.jpg" to "dos")
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM point_photos").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 2
        }
    }

    @Test
    fun `S017b RF-28 la copia del estado anterior lleva las fotos y deshacer las recupera`() {
        addPhoto("id-1", "f-uno", "uno")
        val backup = backupBytes()
        addPhoto("id-2", "f-dos", "dos")
        val rowsBeforeRestore = rows(source(), "point_photos")

        runBlocking { repository.restore(valid(backup)) }.shouldBeInstanceOf<RestoreResult.Restored>()
        photoFiles() shouldBe mapOf("f-uno.jpg" to "uno")

        val saved = File(undoDir, BackupRepository.BEFORE_RESTORE).readBytes()
        zipNames(saved).filter { it.startsWith("photos/") }.sorted() shouldBe listOf("photos/f-dos.jpg", "photos/f-uno.jpg")

        runBlocking { repository.undoRestore() }.shouldBeInstanceOf<RestoreResult.Restored>()

        photoFiles() shouldBe mapOf("f-uno.jpg" to "uno", "f-dos.jpg" to "dos")
        rows(source(), "point_photos") shouldBe rowsBeforeRestore
    }

    @Test
    fun `S017b RF-28 una copia de antes sin fotos se restaura y deja la carpeta de fotos vacia`() {
        val backup = backupBytes()
        addPhoto("id-1", "f-uno", "uno")

        runBlocking { repository.restore(valid(backup)) }.shouldBeInstanceOf<RestoreResult.Restored>()

        photoFiles() shouldBe emptyMap()
        valid(backup).manifest.files.keys.none { it.startsWith("${BackupArchive.PHOTOS_DIR}/") } shouldBe true
    }

    @Test
    fun `S017b RF-30 la copia automatica no lleva las fotos dentro y su manifiesto las nombra con su suma`() {
        addPhoto("id-1", "f-uno", "uno")
        addPhoto("id-2", "f-dos", "dos")
        val target = File(folder.root, "auto.zip")

        val external = runBlocking { repository.photoFilesByName() }
        external.keys shouldBe setOf("f-uno.jpg", "f-dos.jpg")
        val manifest = runBlocking { repository.createBackupFile(target, external) }

        zipNames(target.readBytes()).none { it.startsWith("photos/") } shouldBe true
        manifest.externalPhotos.keys shouldBe setOf("f-uno.jpg", "f-dos.jpg")
        manifest.externalPhotos.getValue("f-uno.jpg") shouldBe
            java.security.MessageDigest.getInstance("SHA-256").digest("uno".toByteArray()).joinToString("") { "%02x".format(it) }
        BackupArchive.readManifest(target.inputStream())!!.externalPhotos shouldBe manifest.externalPhotos
    }

    /** Una copia automatica de ahora y las fotos de la "carpeta" por nombre; devuelve el ZIP leido. */
    private fun automatic(folderPhotos: MutableMap<String, ByteArray>): BackupRead.Valid {
        val target = File(folder.root, "auto-${System.nanoTime()}.zip")
        val external = runBlocking { repository.photoFilesByName() }
        runBlocking { repository.createBackupFile(target, external) }
        external.forEach { (name, file) -> folderPhotos[name] = file.readBytes() }
        return valid(target.readBytes())
    }

    private fun source(photos: Map<String, ByteArray>) = ExternalPhotos { name -> photos[name]?.inputStream() }

    @Test
    fun `S017b RF-32 restaurar una copia automatica recupera las fotos de la carpeta`() {
        addPhoto("id-1", "f-uno", "uno")
        addPhoto("id-2", "f-dos", "dos")
        val inFolder = mutableMapOf<String, ByteArray>()
        val auto = automatic(inFolder)
        File(photosDir, "f-uno.jpg").delete()
        File(photosDir, "f-dos.jpg").delete()

        val result = runBlocking { repository.restore(auto, external = source(inFolder)) }

        result shouldBe RestoreResult.Restored(projects = 2, points = 4, missingPhotos = 0)
        photoFiles() shouldBe mapOf("f-uno.jpg" to "uno", "f-dos.jpg" to "dos")
    }

    @Test
    fun `S017b RF-33 una foto que falta o con otra suma se cuenta y el punto se restaura sin ella`() {
        addPhoto("id-1", "f-uno", "uno")
        addPhoto("id-2", "f-dos", "dos")
        addPhoto("id-3", "f-tres", "tres")
        val inFolder = mutableMapOf<String, ByteArray>()
        val auto = automatic(inFolder)
        inFolder.remove("f-uno.jpg") // falta
        inFolder["f-dos.jpg"] = "otra cosa".toByteArray() // otra suma

        val result = runBlocking { repository.restore(auto, external = source(inFolder)) }

        result shouldBe RestoreResult.Restored(projects = 2, points = 4, missingPhotos = 2)
        photoFiles() shouldBe mapOf("f-tres.jpg" to "tres")
        // Las filas de las fotos se restauran igual: se abriran como una foto que no se puede abrir (RF-17).
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM point_photos").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 3
        }
    }

    @Test
    fun `S017b RF-33 sin carpeta todas las fotos cuentan como no recuperadas`() {
        addPhoto("id-1", "f-uno", "uno")
        addPhoto("id-2", "f-dos", "dos")
        val auto = automatic(mutableMapOf())

        val result = runBlocking { repository.restore(auto, external = null) }

        result shouldBe RestoreResult.Restored(projects = 2, points = 4, missingPhotos = 2)
        photoFiles() shouldBe emptyMap()
    }

    @Test
    fun `S017b RF-32 una carpeta que falla a mitad cuenta las fotos como no recuperadas sin cerrar la app`() {
        addPhoto("id-1", "f-uno", "uno")
        val inFolder = mutableMapOf<String, ByteArray>()
        val auto = automatic(inFolder)
        val broken = ExternalPhotos { error("tarjeta retirada") }

        val result = runBlocking { repository.restore(auto, external = broken) }

        result shouldBe RestoreResult.Restored(projects = 2, points = 4, missingPhotos = 1)
    }
}

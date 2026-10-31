package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
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

/** El paquete lleva las fotos de los puntos del proyecto y de su papelera, y se importan con ids nuevos o sustituyendo (S017b/RF-29). */
@RunWith(AndroidJUnit4::class)
class PackagePhotosTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val mine = "pph-a-${System.nanoTime()}.db"
    private val theirs = "pph-b-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var other: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var myPhotos: File
    private lateinit var theirPhotos: File
    private lateinit var repository: BackupRepository
    private lateinit var otherRepository: BackupRepository
    private lateinit var pkg: ByteArray

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, mine)
        other = TopoPhoneDatabase.build(context, theirs)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"))
        myPhotos = File(folder.newFolder("fa"), "photos").apply { mkdirs() }
        theirPhotos = File(folder.newFolder("fb"), "photos").apply { mkdirs() }
        repository = BackupRepository(
            context,
            db,
            settings.archive,
            "0.17.0",
            workDir = folder.newFolder("wa"),
            undoDir = folder.newFolder("ua"),
            photosDir = myPhotos,
        )
        otherRepository = BackupRepository(
            context,
            other,
            settings.archive,
            "0.17.0",
            workDir = folder.newFolder("wb"),
            undoDir = folder.newFolder("ub"),
            photosDir = theirPhotos,
        )
        addPhoto(db, myPhotos, "id-1", "f-uno", "uno")
        addPhoto(db, myPhotos, "id-4", "f-papelera", "papelera") // el 4 esta en la papelera del proyecto
        addPhoto(db, myPhotos, "id-9", "f-ajena", "ajena") // el 9 es de otro proyecto
        pkg = ByteArrayOutputStream().also { runBlocking { repository.writePackage(fixture.streetId, it) } shouldBe true }.toByteArray()
    }

    @After
    fun close() {
        settings.close()
        db.close()
        other.close()
        context.deleteDatabase(mine)
        context.deleteDatabase(theirs)
    }

    private fun addPhoto(target: TopoPhoneDatabase, dir: File, pointId: String, id: String, content: String) {
        File(dir, "$id.jpg").writeText(content)
        runBlocking { target.photos().insert(PointPhotoEntity(id, pointId, "$id.jpg", "CAMERA", 1, 2, content.length.toLong(), 640, 480)) }
    }

    private fun import(into: BackupRepository, choice: ImportChoice? = null): ImportResult = runBlocking {
        val valid = into.read({ pkg.inputStream() }, BackupKind.PACKAGE).shouldBeInstanceOf<BackupRead.Valid>()
        into.importPackage(valid, choice, importedLabel = "importado")
    }

    private fun photoRows(target: TopoPhoneDatabase): List<PointPhotoEntity> = runBlocking {
        val ids = target.photos().allIds()
        ids.mapNotNull { target.photos().byId(it) }.sortedBy { it.id }
    }

    private fun files(dir: File) = dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() }

    @Test
    fun `S017b RF-29 el paquete lleva las fotos del proyecto y de su papelera pero no las de otro`() {
        val names = buildList { ZipInputStream(pkg.inputStream()).use { while (true) add((it.nextEntry ?: break).name) } }

        names.filter { it.startsWith("photos/") }.sorted() shouldBe listOf("photos/f-papelera.jpg", "photos/f-uno.jpg")
    }

    @Test
    fun `S017b RF-29 importado en otro movil las fotos abren y siguen con su punto`() {
        import(otherRepository).shouldBeInstanceOf<ImportResult.Imported>()

        photoRows(other).map { it.id to it.pointId } shouldBe listOf("f-papelera" to "id-4", "f-uno" to "id-1")
        files(theirPhotos) shouldBe mapOf("f-uno.jpg" to "uno", "f-papelera.jpg" to "papelera")
    }

    @Test
    fun `S017b RF-29 importado como nuevo en el mismo movil las fotos reciben ids nuevos`() {
        val result = import(repository, ImportChoice.AS_NEW).shouldBeInstanceOf<ImportResult.Imported>()

        val rows = photoRows(db)
        rows.size shouldBe 5 // las 3 de antes y 2 nuevas
        val copies = rows.filter { it.id !in setOf("f-uno", "f-papelera", "f-ajena") }
        copies.size shouldBe 2
        copies.forEach { it.pointId shouldBe runBlocking { db.points().byId(it.pointId) }!!.id }
        copies.map { runBlocking { db.points().byId(it.pointId)!!.projectId } }.distinct() shouldBe listOf(result.projectId)
        copies.map { File(myPhotos, "${it.id}.jpg").readText() }.sorted() shouldBe listOf("papelera", "uno")
        files(myPhotos).keys.containsAll(setOf("f-uno.jpg", "f-papelera.jpg", "f-ajena.jpg")) shouldBe true
    }

    @Test
    fun `S017b RF-29 un id de foto que ya existe en otro proyecto recibe uno nuevo`() {
        // La base de datos del otro movil ya tiene una foto con el id del paquete en un proyecto suyo.
        val project = fixtureOther()
        runBlocking { other.photos().insert(PointPhotoEntity("f-uno", project, "x.jpg", "CAMERA", 1, 2, 7, 1, 1)) }
        File(theirPhotos, "f-uno.jpg").writeText("de otro")

        import(otherRepository).shouldBeInstanceOf<ImportResult.Imported>()

        val ids = photoRows(other).map { it.id }
        ids.size shouldBe 3
        File(theirPhotos, "f-uno.jpg").readText() shouldBe "de otro"
        val renamed = photoRows(other).single { it.pointId == "id-1" }
        (renamed.id != "f-uno") shouldBe true
        File(theirPhotos, "${renamed.id}.jpg").readText() shouldBe "uno"
    }

    /** Un punto cualquiera, de un proyecto suyo, en la base de datos del otro movil, para colgarle una foto. */
    private fun fixtureOther(): String = runBlocking {
        val projects = es.topophone.data.project.RoomProjectRepository(other)
        val project = (
            projects.create(
                es.topophone.core.model.project.ProjectDefaults().toDraft().copy(name = "Suyo"),
            ) as es.topophone.data.project.SaveResult.Saved
            ).id
        es.topophone.data.point.RoomPointRepository(other).create(fixture.measured("77", project))
        "id-77"
    }

    @Test
    fun `S017b RF-29 sustituyendo, el proyecto del movil va a la papelera con copias de sus fotos`() {
        // Despues del paquete, el movil cambia el contenido de una foto y anade otra al mismo proyecto.
        File(myPhotos, "f-uno.jpg").writeText("uno cambiada")
        addPhoto(db, myPhotos, "id-2", "f-nueva", "nueva")

        import(repository, ImportChoice.REPLACE).shouldBeInstanceOf<ImportResult.Imported>()

        // El proyecto activo vuelve a tener las fotos del paquete, con sus ids.
        // La copia en la papelera tiene ids nuevos y el contenido que tenia el movil.
        val trashed = photoRows(db).filter { it.id !in setOf("f-uno", "f-papelera", "f-ajena") }
        trashed.map { File(myPhotos, "${it.id}.jpg").readText() }.sorted() shouldBe listOf("nueva", "papelera", "uno cambiada")
        File(myPhotos, "f-uno.jpg").readText() shouldBe "uno"
        photoRows(db).map { it.id }.containsAll(listOf("f-uno", "f-papelera", "f-ajena")) shouldBe true
        photoRows(db).any { it.id == "f-nueva" } shouldBe false
    }

    @Test
    fun `S017b RF-29 un fallo al importar no deja ficheros de fotos copiados`() {
        val before = files(theirPhotos)
        val bad = pkg.copyOf(pkg.size / 2)

        runBlocking { otherRepository.read({ bad.inputStream() }, BackupKind.PACKAGE) }.shouldBeInstanceOf<BackupRead.Damaged>()

        files(theirPhotos) shouldBe before
    }
}

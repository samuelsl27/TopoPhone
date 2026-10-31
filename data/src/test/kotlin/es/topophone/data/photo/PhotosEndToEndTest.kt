package es.topophone.data.photo

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.formats.backup.BackupKind
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.data.backup.BackupFixture
import es.topophone.data.backup.BackupRead
import es.topophone.data.backup.BackupRepository
import es.topophone.data.backup.RestoreResult
import es.topophone.data.backup.TestSettings
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
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
import org.robolectric.annotation.GraphicsMode

/**
 * Extremo a extremo con las fotos de verdad (S017b): una medida con foto, guardar, otra foto tras guardar, la papelera, restaurar, la
 * copia completa, restaurarla y deshacer, siempre con las fotos presentes (RF-2, RF-5, RF-22, RF-24, RF-28).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotosEndToEndTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "e2e-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var settings: TestSettings
    private lateinit var store: PhotoStore
    private lateinit var photos: RoomPhotoRepository
    private lateinit var backups: BackupRepository
    private lateinit var photosDir: File
    private lateinit var jpegs: Map<String, ByteArray>

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        settings = TestSettings(folder.newFolder("settings"), TestSettings.FakeLanguage("es"))
        fun jpeg(width: Int) = ByteArrayOutputStream().also {
            Bitmap.createBitmap(width, 300, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }.toByteArray()
        jpegs = mapOf("durante" to jpeg(400), "despues" to jpeg(500))
        val root = folder.newFolder("files")
        store = PhotoStore(root, { jpegs[it]?.let(::ByteArrayInputStream) }, captureDir = folder.newFolder("capture"))
        photos = RoomPhotoRepository(db, fixture.points, store, clock = { fixture.now })
        photosDir = store.photosDir
        backups = BackupRepository(
            context,
            db,
            settings.archive,
            "0.17.0",
            workDir = folder.newFolder("work"),
            undoDir = folder.newFolder("undo"),
            photosDir = photosDir,
        )
    }

    @After
    fun close() {
        settings.close()
        db.close()
        context.deleteDatabase(name)
    }

    private fun stage(source: String) = (store.stage(source, PhotoOrigin.CAMERA).shouldBeInstanceOf<StageResult.Staged>()).photo

    private fun files() = photosDir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.sorted()

    private fun ids(pointId: String) = runBlocking { photos.photos(pointId).first().map { it.id } }

    @Test
    fun `S017b RF-2 RF-5 RF-22 RF-24 RF-28 la vida entera de las fotos de un punto`() {
        // Una medida con una foto, que se guarda con el punto (RF-2).
        val during = stage("durante")
        val point = fixture.measured("20", fixture.streetId)
        photos.let { runBlocking { it.createWithPhotos(point, listOf(during)) } } shouldBe PointWithPhotosResult.Saved
        ids(point.id) shouldBe listOf(during.id)

        // Otra foto justo después de guardar (RF-5).
        val after = stage("despues")
        runBlocking { photos.addPhotos(point.id, listOf(after)) }.shouldBeInstanceOf<AddPhotosResult.Added>()
        ids(point.id) shouldBe listOf(during.id, after.id)
        files() shouldBe listOf("${during.id}.jpg", "${after.id}.jpg").sorted()

        // El punto a la papelera y vuelta: las fotos van y vuelven con él (RF-24).
        runBlocking { fixture.points.moveToTrash(listOf(point.id)) }
        ids(point.id) shouldBe listOf(during.id, after.id)
        runBlocking { fixture.points.restore(point.id) }
        ids(point.id) shouldBe listOf(during.id, after.id)
        files().size shouldBe 2

        // Copia completa con las dos fotos.
        val backup = ByteArrayOutputStream().also { runBlocking { backups.writeBackup(it) } shouldBe true }.toByteArray()

        // Después de la copia: se borra una foto (RF-22) y se hace otra.
        runBlocking { photos.deletePhoto(during.id) } shouldBe true
        files() shouldBe listOf("${after.id}.jpg")

        // Restaurar la copia devuelve las dos fotos (RF-28)...
        val valid = runBlocking { backups.read({ backup.inputStream() }, BackupKind.BACKUP) }.shouldBeInstanceOf<BackupRead.Valid>()
        runBlocking { backups.restore(valid) }.shouldBeInstanceOf<RestoreResult.Restored>()
        ids(point.id) shouldBe listOf(during.id, after.id)
        files() shouldBe listOf("${during.id}.jpg", "${after.id}.jpg").sorted()
        File(photosDir, "${during.id}.jpg").readBytes().isNotEmpty() shouldBe true

        // ...y deshacer vuelve a lo de antes de restaurar: una sola foto.
        runBlocking { backups.undoRestore() }.shouldBeInstanceOf<RestoreResult.Restored>()
        ids(point.id) shouldBe listOf(after.id)
        files() shouldBe listOf("${after.id}.jpg")
    }
}

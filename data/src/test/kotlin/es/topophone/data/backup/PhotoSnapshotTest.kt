package es.topophone.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.data.backup.BackupFixture.Companion.rows
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.photo.PhotoEventEntity
import es.topophone.data.photo.PointPhotoEntity
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Las filas de las fotos y sus sucesos viajan en la copia completa y en el paquete (S017b/RF-28, RF-29). */
@RunWith(AndroidJUnit4::class)
class PhotoSnapshotTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "photo-snap-${System.nanoTime()}.db"
    private lateinit var db: TopoPhoneDatabase
    private lateinit var fixture: BackupFixture
    private lateinit var source: File
    private lateinit var target: File

    @Before
    fun open() {
        db = TopoPhoneDatabase.build(context, name)
        fixture = BackupFixture(db).fill()
        source = context.getDatabasePath(name)
        target = File(context.cacheDir, "photo-snap-target-${System.nanoTime()}.db")
        runBlocking {
            db.photos().insert(PointPhotoEntity("f1", "id-1", "1_a.jpg", "CAMERA", 10, 11, 250_000, 2_048, 1_536))
            db.photos().insert(PointPhotoEntity("f2", "id-1", "1_b.jpg", "GALLERY", 12, 13, 120_000, 1_600, 1_200))
            db.photos().insertEvent(PhotoEventEntity("e1", "id-1", 14, "ADDED", "1_a.jpg", 10))
        }
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(name)
        target.delete()
    }

    @Test
    fun `S017b RF-28 la copia completa lleva las filas de fotos y sucesos identicas`() {
        DatabaseSnapshot(context, source).copy(target, SnapshotScope.All)

        rows(target, "point_photos") shouldBe rows(source, "point_photos")
        rows(target, "photo_events") shouldBe rows(source, "photo_events")
        rows(target, "point_photos").size shouldBe 2
    }

    @Test
    fun `S017b RF-29 el paquete lleva las fotos de los puntos de su proyecto`() {
        DatabaseSnapshot(context, source).copy(target, SnapshotScope.Project(fixture.streetId))

        rows(target, "point_photos").size shouldBe 2
        rows(target, "photo_events").size shouldBe 1
    }
}

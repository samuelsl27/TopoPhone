package es.topophone.app.ui.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.PointFixtures.measured
import es.topophone.core.formats.csv.CsvOptions
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.export.ExportPreferences
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import kotlin.time.measureTimedValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exportar 10 000 puntos con su base sigue dentro del límite de S012 (menos de 3 s): la base se pasa al SC una vez por
 * posición (S015, RNF de la spec; S015/RF-22). Robolectric sirve de cota; la medida en el móvil es de la demo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ExportBasePerformanceTest {

    private class FixedPreferences : ExportPreferences {
        override val options = MutableStateFlow(CsvOptions())

        override suspend fun save(options: CsvOptions) {
            this.options.value = options
        }
    }

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `S015 RNF 10 000 puntos con su base se exportan en menos de 3 s`() {
        val projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Parcelaria")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val base = PointBase(GeodeticPosition(40.4166667, -3.7, 700.0), BaseSource.RECEIVER, null, null, 312.25)
        val points = List(POINTS) { i ->
            measured("P$i", projectId = projectId, createdAtMs = i.toLong()).let {
                it.copy(measurement = it.measurement!!.copy(base = base))
            }
        }
        val vm = ExportViewModel(FakePointRepository(points), projects, FixedPreferences(), ExportSelection())
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
        vm.start(projectId, selectionOnly = false)
        runBlocking { withTimeout(10_000) { vm.state.first { it.loaded } } }
        val out = ByteArrayOutputStream()

        val (written, elapsed) = measureTimedValue { runBlocking { vm.writeTo(out) } }

        written shouldBe true
        out.toString(Charsets.UTF_8.name()).count { it == '\n' } shouldBe POINTS + 1
        elapsed.inWholeMilliseconds shouldBeLessThan 3_000L
    }

    private companion object {
        const val POINTS = 10_000
    }
}

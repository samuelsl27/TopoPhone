package es.topophone.app.ui.points

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.log.RecordingLog
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.formats.csv.DelimitedReader
import es.topophone.core.formats.csv.TextDecoding
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.ImportAnalyzer
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Con 50.000 filas, el fichero se lee y valida en menos de 3 s; cambiar una opción actualiza la vista previa en menos de 1 s con los
 * 10.000 puntos que admite un proyecto, e importarlos tarda menos de 5 s (S028, RNF de la spec). Robolectric sirve de cota; la medida
 * en el móvil es de la demo.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PointImportPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val projectId = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Parcelaria")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    /** Un CSV de [rows] puntos en el huso 30, con cabecera, descripción con comas entre comillas y coma decimal opcional. */
    private fun csv(rows: Int): ByteArray = buildString {
        append("Name,Easting,Northing,Elevation,Description\r\n")
        repeat(rows) { i ->
            append('P').append(i).append(',')
            append(440_000 + i % 500).append('.').append(i % 1000).append(',')
            append(4_474_000 + i / 500).append('.').append(i % 977).append(',')
            append(640 + i % 50).append('.').append(i % 10).append(',')
            append("\"Bordillo, tramo ").append(i).append("\"\r\n")
        }
    }.toByteArray()

    @Test
    fun `S028 RNF 50000 filas se leen y validan en menos de 3 s`() {
        val bytes = csv(50_000)
        val options = ImportOptions(
            encoding = ImportEncoding.UTF8,
            separator = ImportSeparator.COMMA,
            decimal = ImportDecimal.POINT,
            hasHeader = true,
            mapping = ColumnMapping.penzd(5),
        )
        val analyzer = ImportAnalyzer()
        // Calienta la JVM: la medida es del régimen normal, no de la carga de clases.
        analyzer.analyze(
            DelimitedReader.rows(TextDecoding.reader(ByteArrayInputStream(csv(500)), options.encoding), options.separator),
            options,
            ProjectCrs.ETRS89_UTM30,
            HeightSystem.ORTHOMETRIC,
            emptySet(),
        )

        lateinit var counts: es.topophone.core.model.point.ImportCounts
        val elapsed = measureTime {
            counts = analyzer.analyze(
                DelimitedReader.rows(TextDecoding.reader(ByteArrayInputStream(bytes), options.encoding), options.separator),
                options,
                ProjectCrs.ETRS89_UTM30,
                HeightSystem.ORTHOMETRIC,
                emptySet(),
            ).counts
        }

        counts.rows shouldBe 50_000
        counts.valid shouldBe 50_000
        println("S028 50.000 filas leídas y validadas en ${elapsed.inWholeMilliseconds} ms")
        elapsed.inWholeMilliseconds shouldBeLessThan 3_000
    }

    @Test
    fun `S028 RNF un cambio de opcion con 10000 filas tarda menos de 1 s`() {
        val vm = PointImportViewModel(FakePointRepository(), projects, UnconfinedTestDispatcher(), RecordingLog())
        vm.debounceMs = 0
        vm.start(projectId, ImportSource("grande.csv", null) { ByteArrayInputStream(csv(10_000)) })
        vm.settled().counts.valid shouldBe 10_000

        val elapsed = measureTime {
            vm.setZeroAsNoHeight(true)
            vm.settled()
        }

        println("S028 cambio de opción con 10.000 filas en ${elapsed.inWholeMilliseconds} ms")
        elapsed.inWholeMilliseconds shouldBeLessThan 1_000
    }

    @Test
    fun `S028 RNF 10000 puntos se importan en menos de 5 s`() {
        val repository = RoomPointRepository(db)
        val vm = PointImportViewModel(repository, projects, UnconfinedTestDispatcher(), RecordingLog())
        vm.debounceMs = 0
        vm.start(projectId, ImportSource("grande.csv", null) { ByteArrayInputStream(csv(10_000)) })
        vm.settled().canImport shouldBe true

        val elapsed = measureTime {
            vm.import()
            vm.settled()
        }

        vm.state.value.stage shouldBe ImportStage.RESULT
        val saved = runBlocking { repository.points(projectId).first() }
        saved.size shouldBe 10_000
        saved.all { it.origin == PointOrigin.IMPORTED } shouldBe true
        println("S028 10.000 puntos importados en ${elapsed.inWholeMilliseconds} ms")
        elapsed.inWholeMilliseconds shouldBeLessThan 5_000
    }
}

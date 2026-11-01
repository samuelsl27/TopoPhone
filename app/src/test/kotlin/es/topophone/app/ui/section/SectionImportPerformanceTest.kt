package es.topophone.app.ui.section

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.points.ImportSource
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Importar un LandXML con 2.000 secciones de 11 puntos, de leerlo a guardarlo, tarda menos de 5 s (S050, requisito no funcional). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SectionImportPerformanceTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun xml(count: Int): ByteArray {
        val builder = StringBuilder(
            "<?xml version=\"1.0\"?><LandXML xmlns=\"http://www.landxml.org/schema/LandXML-1.2\" version=\"1.2\">" +
                "<Units><Metric linearUnit=\"meter\"/></Units><CrossSects name=\"S\">",
        )
        for (i in 0 until count) {
            builder.append("<CrossSect sta=\"").append(i * 10).append("\"><CrossSectSurf name=\"R\" state=\"proposed\">")
            for (j in -5..5) {
                builder.append("<CrossSectPnt code=\"P").append(j + 5).append("\">").append(j * 1.5).append(' ').append(
                    100.0 + i * 0.01 - kotlin.math.abs(j) * 0.03,
                )
                    .append("</CrossSectPnt>")
            }
            builder.append("</CrossSectSurf></CrossSect>")
        }
        return builder.append("</CrossSects></LandXML>").toString().toByteArray()
    }

    @Test
    fun `S050 RNF importar un LandXML con 2000 secciones tarda menos de 5 s`() {
        val projects = RoomProjectRepository(db)
        val alignments = RoomAlignmentRepository(db)
        val sections = RoomSectionRepository(db)
        val projectId = runBlocking {
            projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
        }
        val axisId = runBlocking {
            alignments.add(
                projectId,
                listOf(
                    AlignmentToSave(
                        "Eje largo",
                        null,
                        AlignmentSource.MANUAL,
                        listOf(AlignmentElement.line(0.0, 25_000.0, 440_000.0, 4_474_000.0, PI / 2)),
                        null,
                        AlignmentExtent(440_000.0, 465_000.0, 4_474_000.0, 4_474_000.0),
                        false,
                    ),
                ),
                1L,
            ).ids.single()
        }
        val data = xml(2_000)
        val vm = SectionImportViewModel(sections, alignments, projects, dispatcher)

        val start = System.nanoTime()
        vm.start(
            projectId,
            axisId,
            SectionImportKind.LANDXML,
            ImportSource("secciones.xml", data.size.toLong()) {
                ByteArrayInputStream(data)
            },
        )
        runBlocking { withTimeout(20_000) { vm.state.first { it.stage == SectionImportStage.CONFIG } } }
        vm.import()
        runBlocking { withTimeout(20_000) { vm.state.first { it.stage == SectionImportStage.DONE } } }
        val seconds = (System.nanoTime() - start) / 1e9

        vm.state.value.savedCount shouldBe 2_000
        vm.state.value.rejected shouldBe emptyList()
        runBlocking { sections.sections(axisId).first() }.size shouldBe 2_000
        seconds shouldBeLessThan 5.0
    }
}

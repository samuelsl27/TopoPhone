package es.topophone.app.ui.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.MapCamera
import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.TemplateSegment
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.SectionResolver
import es.topophone.data.alignment.AlignmentToSave
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import es.topophone.data.section.RoomSectionRepository
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.Locale
import kotlin.math.PI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** Los ejes en el mapa: líneas, eje activo destacado y marcas rotuladas de PK según la escala (S049/RF-17, RF-19). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class AlignmentSceneTest {
    private val crs = ProjectCrs.ETRS89_UTM30
    private val es = Locale.forLanguageTag("es-ES")

    private fun axis(id: String, e0: Double, startChainage: Double = 0.0, length: Double = 1000.0, visible: Boolean = true) = Alignment(
        id = id,
        projectId = "p",
        name = id,
        fileName = null,
        importedAtMs = 0L,
        source = AlignmentSource.MANUAL,
        elements = listOf(AlignmentElement.line(startChainage, length, e0, 4474000.0, PI / 2)),
        profile = null,
        visible = visible,
        extent = AlignmentExtent(e0, e0 + length, 4474000.0, 4474000.0),
    )

    private fun build(
        alignments: List<Alignment>,
        active: String? = null,
        mpp: Double = 1.0,
        viewport: CadExtent? = null,
        unit: LinearUnit = LinearUnit.METRE,
        crs: ProjectCrs = this.crs,
    ) = AlignmentSceneBuilder.build(alignments, active, crs, unit, es, mpp, viewport)

    @Test
    fun `S049 RF-17 cada eje visible da una linea y el activo va marcado`() {
        val scene = build(listOf(axis("a", 440000.0), axis("b", 441000.0)), active = "b").shouldNotBeNull()

        scene.lines.size shouldBe 2
        scene.lines.map { it.active } shouldBe listOf(false, true)
        (scene.lines[0].vertexCount >= 2) shouldBe true
    }

    @Test
    fun `S049 RF-19 un eje oculto no se dibuja ni tiene marcas`() {
        val scene = build(listOf(axis("a", 440000.0), axis("b", 441000.0, visible = false))).shouldNotBeNull()

        scene.lines.size shouldBe 1
        scene.marks.size shouldBe 11 // las del eje a: del PK 0 al 1000 cada 100 m
        build(listOf(axis("a", 440000.0, visible = false))).shouldBeNull()
    }

    @Test
    fun `S049 RF-17 las coordenadas de la linea son las geograficas del eje`() {
        val scene = build(listOf(axis("a", 440000.0))).shouldNotBeNull()
        val (lat, lon) = CadPlacement.geographic(crs, 440000.0, 4474000.0)!!

        scene.lines.single().lonLat[0] shouldBe (lon plusOrMinus 1e-12)
        scene.lines.single().lonLat[1] shouldBe (lat plusOrMinus 1e-12)
    }

    @Test
    fun `S049 RF-17 las marcas salen a un intervalo redondo segun la escala y se rotulan con el PK`() {
        val scene = build(listOf(axis("a", 440000.0, startChainage = 1000.5)), mpp = 1.0).shouldNotBeNull()

        // 1 m por píxel: 90 m de separación mínima, 100 m de intervalo
        scene.marks.map { it.label } shouldBe listOf(
            "1+100", "1+200", "1+300", "1+400", "1+500", "1+600", "1+700", "1+800", "1+900", "2+000",
        )
    }

    @Test
    fun `S049 RF-17 acercando el mapa el intervalo baja y alejandolo sube`() {
        val near = build(listOf(axis("a", 440000.0)), mpp = 0.1).shouldNotBeNull()
        val far = build(listOf(axis("a", 440000.0)), mpp = 10.0).shouldNotBeNull()

        near.marks.map { it.label }.take(3) shouldBe listOf("0+000", "0+010", "0+020")
        far.marks.map { it.label } shouldBe listOf("0+000", "1+000")
    }

    @Test
    fun `S049 RF-17 las marcas del eje activo van marcadas como activas`() {
        val scene = build(listOf(axis("a", 440000.0), axis("b", 442000.0)), active = "a").shouldNotBeNull()

        scene.marks.filter { it.active }.size shouldBe scene.marks.size / 2
        scene.marks.first().active shouldBe true
    }

    @Test
    fun `S049 RF-17 solo se dibujan los ejes y las marcas que caen en la vista`() {
        val view = CadExtent(440350.0, 440750.0, 4473900.0, 4474100.0)

        val scene = build(listOf(axis("a", 440000.0), axis("lejos", 450000.0)), viewport = view).shouldNotBeNull()

        scene.lines.size shouldBe 1
        scene.marks.map { it.label } shouldBe listOf("0+400", "0+500", "0+600", "0+700")
    }

    @Test
    fun `S049 RNF-T9 en pies el PK se rotula en estaciones de 100 pies`() {
        val scene = build(listOf(axis("a", 440000.0, length = 600.0)), mpp = 1.0, unit = LinearUnit.INTERNATIONAL_FOOT).shouldNotBeNull()

        // 1 m/px son 3,28 ft/px: 295 ft mínimo y un intervalo de 500 ft = 152,4 m
        scene.marks.map { it.label } shouldBe listOf("0+00", "5+00", "10+00", "15+00")
    }

    @Test
    fun `S049 RF-17 un proyecto en geograficas no dibuja ejes`() {
        build(listOf(axis("a", 440000.0)), crs = ProjectCrs.ETRS89_GEOGRAPHIC).shouldBeNull()
    }

    @Test
    fun `S049 RF-17 una curva se muestrea con mas vertices que una recta`() {
        val curve = axis("c", 440000.0).copy(
            elements = listOf(AlignmentElement.arc(0.0, 300.0, 440000.0, 4474000.0, PI / 2, -1.0 / 150)),
        )

        val scene = build(listOf(axis("a", 440000.0), curve), mpp = 0.05).shouldNotBeNull()

        val straight = scene.lines[0].vertexCount
        (scene.lines[1].vertexCount > straight) shouldBe true
    }

    @Test
    fun `S049 RF-17 la escena sale del repositorio con la camara y respeta ocultar un eje`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val projects = RoomProjectRepository(db)
            val repository = RoomAlignmentRepository(db)
            val projectId = runBlocking {
                projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
            }
            val ids = runBlocking {
                repository.add(
                    projectId,
                    listOf("a", "b").mapIndexed { i, name ->
                        AlignmentToSave(
                            name,
                            null,
                            AlignmentSource.MANUAL,
                            listOf(AlignmentElement.line(0.0, 500.0, 440000.0 + i * 200, 4474000.0, PI / 2)),
                            null,
                            AlignmentExtent(440000.0 + i * 200, 440500.0 + i * 200, 4474000.0, 4474000.0),
                            false,
                        )
                    },
                    1L,
                ).ids
            }
            val vm = AlignmentMapViewModel(repository, projects, RoomSectionRepository(db), dispatcher)
            vm.start(projectId)
            val (lat, lon) = CadPlacement.geographic(crs, 440300.0, 4474000.0)!!
            vm.onCamera(MapCamera(lat, lon, 16.0))

            val scene = runBlocking { withTimeout(10_000) { vm.scene.first { it != null } } }.shouldNotBeNull()
            scene.lines.size shouldBe 2
            scene.lines.count { it.active } shouldBe 1 // el primero que se importó es el activo

            runBlocking { repository.setVisible(ids[1], false) }
            runBlocking { withTimeout(10_000) { vm.scene.first { it?.lines?.size == 1 } } }.shouldNotBeNull()
        } finally {
            Dispatchers.resetMain()
            db.close()
        }
    }

    private val flatTemplate = SectionTemplate(
        0.0,
        1000.0,
        "EJE",
        listOf(TemplateSegment("BI", 3.5, 0.02, true)),
        listOf(TemplateSegment("BD", 3.5, 0.02, true)),
    )

    @Test
    fun `S050 RF-14 una linea por codigo de seccion sobre el eje y a su distancia`() {
        val resolver = SectionResolver(listOf(flatTemplate), null, emptyList())

        val scene = AlignmentSceneBuilder.build(
            listOf(axis("a", 440000.0)),
            null,
            crs,
            LinearUnit.METRE,
            es,
            1.0,
            null,
            mapOf(
                "a" to resolver,
            ),
        )
            .shouldNotBeNull()

        scene.sectionLines.map { it.code }.sorted() shouldBe listOf("BD", "BI", "EJE")
        // El eje va al Este: la derecha queda al Sur. La línea BD empieza a 3,5 m al Sur del origen.
        val (lat, lon) = CadPlacement.geographic(crs, 440000.0, 4474000.0 - 3.5)!!
        val bd = scene.sectionLines.first { it.code == "BD" }
        bd.lonLat[0] shouldBe (lon plusOrMinus 1e-8)
        bd.lonLat[1] shouldBe (lat plusOrMinus 1e-8)
    }

    @Test
    fun `S050 RF-14 sin pedirlas no hay lineas de seccion y un eje sin seccion tampoco`() {
        build(listOf(axis("a", 440000.0))).shouldNotBeNull().sectionLines shouldBe emptyList()
        val empty = SectionResolver(emptyList(), null, emptyList())

        AlignmentSceneBuilder.build(listOf(axis("a", 440000.0)), null, crs, LinearUnit.METRE, es, 1.0, null, mapOf("a" to empty))
            .shouldNotBeNull().sectionLines shouldBe emptyList()
    }

    @Test
    fun `S050 RF-14 donde dos secciones no se pueden interpolar la linea se corta`() {
        val design = listOf(0.0, 100.0, 200.0).map { pk ->
            CrossSection(
                SectionKind.DESIGN,
                pk,
                HeightKind.ABSOLUTE,
                if (pk ==
                    200.0
                ) {
                    listOf(SectionPoint("EJE", 0.0, 1.0))
                } else {
                    listOf(SectionPoint("EJE", 0.0, 1.0), SectionPoint("X", 5.0, 1.0))
                },
            )
        }
        // Entre 0 y 100 las dos secciones tienen EJE y X; entre 100 y 200 no coinciden y no hay sección interpolable.
        val resolver = SectionResolver(emptyList(), null, design)

        val scene = AlignmentSceneBuilder.build(
            listOf(axis("a", 440000.0)),
            null,
            crs,
            LinearUnit.METRE,
            es,
            1.0,
            null,
            mapOf(
                "a" to resolver,
            ),
        )
            .shouldNotBeNull()

        val (_, lonAt200) = CadPlacement.geographic(crs, 440200.0, 4474000.0)!!
        for ((code, shift) in listOf("EJE" to 0.0, "X" to 5.0)) {
            val (_, expectedLon) = CadPlacement.geographic(crs, 440100.0, 4474000.0 - shift)!!
            val line = scene.sectionLines.single { it.code == code }
            line.lonLat[line.lonLat.size - 2] shouldBe (expectedLon plusOrMinus 1e-8)
            (line.lonLat[line.lonLat.size - 2] < lonAt200) shouldBe true
        }
    }

    @Test
    fun `S050 RF-14 el interruptor de las lineas de seccion sale del ViewModel y no se recuerda`() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        val dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            val projects = RoomProjectRepository(db)
            val repository = RoomAlignmentRepository(db)
            val sections = RoomSectionRepository(db)
            val projectId = runBlocking {
                projects.create(ProjectDefaults().toDraft().copy(name = "Obra")).shouldBeInstanceOf<SaveResult.Saved>().id
            }
            val id = runBlocking {
                repository.add(
                    projectId,
                    listOf(
                        AlignmentToSave(
                            "a",
                            null,
                            AlignmentSource.MANUAL,
                            listOf(AlignmentElement.line(0.0, 500.0, 440000.0, 4474000.0, PI / 2)),
                            null,
                            AlignmentExtent(440000.0, 440500.0, 4474000.0, 4474000.0),
                            false,
                        ),
                    ),
                    1L,
                ).ids.single()
            }
            runBlocking { sections.addTemplate(id, flatTemplate.copy(toChainage = 500.0)) }
            val vm = AlignmentMapViewModel(repository, projects, sections, dispatcher)
            vm.start(projectId)
            val (lat, lon) = CadPlacement.geographic(crs, 440300.0, 4474000.0)!!
            vm.onCamera(MapCamera(lat, lon, 16.0))
            runBlocking { withTimeout(10_000) { vm.scene.first { it != null } } }.shouldNotBeNull().sectionLines shouldBe emptyList()

            vm.setSectionLines(id, true)

            vm.sectionLines.value shouldBe setOf(id)
            runBlocking { withTimeout(10_000) { vm.scene.first { it?.sectionLines?.isNotEmpty() == true } } }.shouldNotBeNull()
                .sectionLines.map { it.code }.sorted() shouldBe listOf("BD", "BI", "EJE")
            vm.setSectionLines(id, false)
            runBlocking { withTimeout(10_000) { vm.scene.first { it?.sectionLines?.isEmpty() == true } } }.shouldNotBeNull()
        } finally {
            Dispatchers.resetMain()
            db.close()
        }
    }
}

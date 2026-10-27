package es.topophone.data.alignment

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.survey.AlignmentDraft
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Importar ejes de un LandXML o de un borrador: resumen, ignorados, una sola transacción y cancelar (S049/RF-2 a RF-5, RF-9 a RF-13). */
@RunWith(AndroidJUnit4::class)
class AlignmentImporterTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        TopoPhoneDatabase::class.java,
    ).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomAlignmentRepository(db)
    private val importer = AlignmentImporter(repository) { 7_000L }
    private val crs = ProjectCrs.ETRS89_UTM30

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Obra lineal")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private fun bytes(name: String) = File(root, "fixtures/ejes/$name").readBytes()

    private fun open(name: String): () -> java.io.InputStream = { ByteArrayInputStream(bytes(name)) }

    private suspend fun import(
        name: String,
        vararg indices: Int,
        unit: LinearUnit = LinearUnit.METRE,
        source: CadSourceCrs = CadSourceCrs.Project,
    ) = importer.importLandXml(project, crs, name, indices.toSet(), source, unit, open(name))

    private fun done(result: AlignmentImportResult) = result.shouldBeInstanceOf<AlignmentImportResult.Done>().summary

    @Test
    fun `S049 RF-2 la exploracion lista las alineaciones del fichero`() = runBlocking<Unit> {
        val scan = importer.scan(open("ejes.xml")).shouldBeInstanceOf<AlignmentScanResult.Found>().scan

        scan.alignments.map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
    }

    @Test
    fun `S049 RF-3 sin alineaciones o con un fichero que no vale la exploracion dice el motivo`() = runBlocking<Unit> {
        importer.scan(open("sin-alineaciones.xml")).shouldBeInstanceOf<AlignmentScanResult.Found>().scan.alignments shouldBe emptyList()
        importer.scan(open("mal-formado.xml")).shouldBeInstanceOf<AlignmentScanResult.Failed>().reason shouldBe
            AlignmentImportFailure.MALFORMED
        importer.scan(open("no-es-landxml.txt")).shouldBeInstanceOf<AlignmentScanResult.Failed>().reason shouldBe
            AlignmentImportFailure.MALFORMED
        importer.scan(openStream = {
            ByteArrayInputStream("<html/>".toByteArray())
        }).shouldBeInstanceOf<AlignmentScanResult.Failed>().reason shouldBe
            AlignmentImportFailure.NOT_LANDXML
    }

    @Test
    fun `S049 RF-13 los tres ejes se importan con su resumen y el primero queda activo`() = runBlocking<Unit> {
        val summary = done(import("ejes.xml", 0, 1, 2))

        summary.ignored shouldBe emptyList()
        summary.imported.map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
        summary.imported.map { it.lengthM } shouldBe listOf(420.0, 380.0, 200.0)
        summary.imported.map { it.startChainage } shouldBe listOf(0.0, 1000.5, 0.0)
        summary.imported.map { it.hasProfile } shouldBe listOf(true, true, false)
        summary.activatedId shouldBe summary.imported.first().id
        val saved = repository.summaries(project).first()
        saved.map { it.name } shouldBe listOf("Eje A", "Eje B", "Eje C")
        saved.all { it.importedAtMs == 7_000L && it.fileName == "ejes.xml" && it.source == AlignmentSource.LANDXML } shouldBe true
    }

    @Test
    fun `S049 RF-13 importar dos veces el mismo fichero crea otros ejes aparte`() = runBlocking<Unit> {
        done(import("ejes.xml", 0))
        val second = done(import("ejes.xml", 0))

        second.activatedId.shouldBeNull()
        repository.summaries(project).first().size shouldBe 2
    }

    @Test
    fun `S049 RF-11 en pies el eje queda en metros`() = runBlocking<Unit> {
        val summary = done(import("eje-a-pies.xml", 0, unit = LinearUnit.INTERNATIONAL_FOOT))

        summary.imported.single().lengthM shouldBe (420.0 plusOrMinus 1e-9)
        val alignment = repository.alignment(summary.imported.single().id).shouldNotBeNull()
        alignment.elements.first().startEasting shouldBe (440000.0 plusOrMinus 1e-6)
        alignment.profile.shouldNotBeNull().vertices[1].elevation shouldBe (103.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-5 un hueco o un tipo no admitido deja el eje fuera con el tramo`() = runBlocking<Unit> {
        done(import("eje-hueco.xml", 0)).ignored.single().issue.shouldBeInstanceOf<AlignmentIssue.Gap>().element shouldBe 2
        done(import("eje-irregular.xml", 0)).ignored.single().issue shouldBe AlignmentIssue.UnsupportedElement(1, "IrregularLine")
        done(import("eje-espiral-cubica.xml", 0)).ignored.single().issue shouldBe AlignmentIssue.UnsupportedElement(2, "cubic")
        repository.summaries(project).first() shouldBe emptyList()
    }

    @Test
    fun `S049 D3 las ecuaciones de PK dejan el eje fuera y un acuerdo asimetrico solo la rasante`() = runBlocking<Unit> {
        done(import("eje-ecuacion-pk.xml", 0)).ignored.single().issue shouldBe AlignmentIssue.StationEquation

        val summary = done(import("eje-acuerdo-asimetrico.xml", 0))

        summary.imported.single().hasProfile shouldBe false
        summary.imported.single().profileOmission shouldBe ProfileOmission.UnsupportedCurve("UnsymParaCurve")
    }

    @Test
    fun `S049 RF-10 las rasantes erroneas se omiten con su vertice y los ejes se importan`() = runBlocking<Unit> {
        val summary = done(import("eje-rasante-mala.xml", 0, 1, 2))

        summary.imported.map { it.hasProfile } shouldBe listOf(false, false, false)
        summary.imported.map { it.profileOmission } shouldBe listOf(
            ProfileOmission.Invalid(ProfileIssue.RepeatedChainage(3, 100.0)),
            ProfileOmission.Invalid(ProfileIssue.OutsideAxis(3, 250.0)),
            ProfileOmission.Invalid(ProfileIssue.CurveDoesNotFit(2, 100.0)),
        )
    }

    @Test
    fun `S049 RF-12 un eje fuera del ambito se ignora y no se guarda nada`() = runBlocking<Unit> {
        // Eje A tomado como UTM 36 (Turquía y Chipre): sus vértices caen fuera del ámbito de España.
        val summary = done(import("ejes.xml", 0, source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 36)))

        summary.imported shouldBe emptyList()
        summary.ignored.single().issue shouldBe AlignmentIssue.OutsideCrsArea
        repository.summaries(project).first() shouldBe emptyList()
    }

    @Test
    fun `S049 RF-11 en otro sistema el eje se guarda aproximado y con rectas`() = runBlocking<Unit> {
        val summary = done(import("ejes.xml", 0, source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)))

        val id = summary.imported.single().id
        summary.imported.single().approximated shouldBe true
        repository.alignment(id)!!.approximated shouldBe true
    }

    @Test
    fun `S049 RF-3 un fichero sin las alineaciones pedidas o mal formado falla con su motivo`() = runBlocking<Unit> {
        import("ejes.xml").shouldBeInstanceOf<AlignmentImportResult.Failed>().reason shouldBe AlignmentImportFailure.NO_ALIGNMENT
        import("mal-formado.xml", 0).shouldBeInstanceOf<AlignmentImportResult.Failed>().reason shouldBe AlignmentImportFailure.MALFORMED
    }

    @Test
    fun `S049 RF-13 si falla la escritura no queda nada guardado`() = runBlocking<Unit> {
        val failing = object : AlignmentRepository by repository {
            override suspend fun add(projectId: String, items: List<AlignmentToSave>, importedAtMs: Long): AlignmentsAdded =
                error("disco lleno")
        }

        val result = AlignmentImporter(failing).importLandXml(
            project,
            crs,
            "ejes.xml",
            setOf(0, 1),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            open("ejes.xml"),
        )

        result.shouldBeInstanceOf<AlignmentImportResult.Failed>().reason shouldBe AlignmentImportFailure.WRITE_ERROR
        repository.summaries(project).first() shouldBe emptyList()
    }

    @Test
    fun `S049 RF-13 cancelar la importacion no deja nada guardado`() = runBlocking<Unit> {
        lateinit var job: kotlinx.coroutines.Job
        val deferred = async(start = CoroutineStart.LAZY) {
            importer.importLandXml(
                project,
                crs,
                "ejes.xml",
                setOf(0, 1, 2),
                CadSourceCrs.Project,
                LinearUnit.METRE,
                {
                    job.cancel()
                    ByteArrayInputStream(bytes("ejes.xml"))
                },
            )
        }
        job = deferred
        deferred.start()

        runCatching { deferred.await() }.exceptionOrNull().shouldBeInstanceOf<CancellationException>()

        repository.summaries(project).first() shouldBe emptyList()
    }

    @Test
    fun `S049 RF-6 un borrador de una polilinea o a mano se importa con su origen`() = runBlocking<Unit> {
        val draft = AlignmentDraft(listOf(AlignmentElement.line(250.0, 100.0, 440000.0, 4474000.0, 1.0)))

        val summary = done(importer.importDraft(project, crs, "Zanja", null, AlignmentSource.MANUAL, draft))

        val saved = repository.summaries(project).first().single()
        saved.name shouldBe "Zanja"
        saved.source shouldBe AlignmentSource.MANUAL
        saved.fileName.shouldBeNull()
        saved.startChainage shouldBe 250.0
        summary.activatedId shouldBe saved.id
    }

    @Test
    fun `S049 RF-12 un borrador fuera del ambito no se guarda y dice el motivo`() = runBlocking<Unit> {
        val draft = AlignmentDraft(listOf(AlignmentElement.line(0.0, 100.0, 0.0, 0.0, 1.0)))

        val summary = done(importer.importDraft(project, crs, "Lejos", null, AlignmentSource.MANUAL, draft))

        summary.imported shouldBe emptyList()
        summary.ignored.single() shouldBe IgnoredAlignment("Lejos", AlignmentIssue.OutsideCrsArea)
    }

    @Test
    fun `S049 RF-9 una rasante valida se asocia al eje y una con PK repetidos se rechaza`() = runBlocking<Unit> {
        val draft = AlignmentDraft(listOf(AlignmentElement.line(0.0, 200.0, 440000.0, 4474000.0, 1.0)))
        val id = done(importer.importDraft(project, crs, "Eje", null, AlignmentSource.MANUAL, draft)).imported.single().id

        importer.attachProfile(id, listOf(VerticalVertex(0.0, 10.0), VerticalVertex(200.0, 12.0))) shouldBe ProfileAttachResult.Attached
        repository.alignment(id)!!.profile.shouldNotBeNull().vertices.size shouldBe 2
        importer.attachProfile(id, listOf(VerticalVertex(0.0, 10.0), VerticalVertex(0.0, 11.0), VerticalVertex(100.0, 12.0))) shouldBe
            ProfileAttachResult.Rejected(ProfileIssue.RepeatedChainage(2, 0.0))
        importer.attachProfile("no-existe", emptyList()) shouldBe ProfileAttachResult.AxisNotFound
    }
}

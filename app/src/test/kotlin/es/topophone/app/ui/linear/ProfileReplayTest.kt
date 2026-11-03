package es.topophone.app.ui.linear

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.alignment.AlignmentTestFiles
import es.topophone.app.ui.receiver.repositoryRoot
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.core.model.survey.linear.SurveyFlag
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.ProfileSampler
import es.topophone.core.survey.VerticalProfileEval
import es.topophone.data.alignment.AlignmentImporter
import es.topophone.data.alignment.RoomAlignmentRepository
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Las reproducciones sintéticas `reproduccion-longitudinal` y `reproduccion-transversal` (`tools/ejes/generar_toma_lineal.py`, con lo
 * esperado calculado aparte en el plano) por la `ReceiverSession` real y el muestreo del perfil, época a época y a 1 mm: el PK, el
 * desplazamiento, la diferencia de cota y la marca de cada punto (S052/RF-11, RF-12, RF-17, RF-18; constitución nº 2).
 */
@RunWith(AndroidJUnit4::class)
class ProfileReplayTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val harness = ReplayHarness()
    private val directory = File(repositoryRoot, "fixtures/ejes/toma")

    @After
    fun tearDown() {
        harness.close()
        db.close()
    }

    private fun rows(name: String) = File(directory, name).readLines(Charsets.UTF_8).filter {
        it.isNotBlank()
    }.drop(1).map { it.split(",") }

    private fun axis(index: Int) = runBlocking {
        val alignments = RoomAlignmentRepository(db)
        val project = (RoomProjectRepository(db).create(ProjectDefaults().toDraft().copy(name = "Obra")) as SaveResult.Saved).id
        AlignmentImporter(alignments).importLandXml(
            project,
            ProjectCrs.ETRS89_UTM30,
            "ejes.xml",
            setOf(index),
            CadSourceCrs.Project,
            LinearUnit.METRE,
            { ByteArrayInputStream(AlignmentTestFiles.bytes("ejes.xml")) },
        )
        alignments.alignment(alignments.summaries(project).first().single().id).shouldNotBeNull()
    }

    private fun flagOf(name: String) = when (name) {
        "ninguna" -> SurveyFlag.NONE
        "fuera_del_eje" -> SurveyFlag.OUT_OF_AXIS
        "fuera_de_seccion" -> SurveyFlag.OUT_OF_SECTION
        else -> SurveyFlag.NO_PROJECTION
    }

    @Test
    fun `S052 RF-11 y RF-12 el recorrido del eje A da el PK el desplazamiento la diferencia de cota y la marca de cada epoca a 1 mm`() {
        val eje = axis(0)
        val geometry = AlignmentGeometry(eje.elements)
        val grade = VerticalProfileEval(eje.profile.shouldNotBeNull())
        val expected = rows("reproduccion-longitudinal.csv")
        val positions = harness.positions(File(directory, "reproduccion-longitudinal.nmea"), expected.size)

        positions.size shouldBe expected.size - 1
        var compared = 0
        for ((position, row) in positions.zip(expected)) {
            val tip = position.shouldNotBeNull()
            val sample = ProfileSampler.longitudinal(geometry, grade, tip.easting!!, tip.northing!!, tip.height, AXIS_TOLERANCE_M)

            sample.flag shouldBe flagOf(row[8])
            if (row[1].isEmpty()) {
                sample.chainageM.shouldBeNull()
            } else {
                sample.chainageM.shouldNotBeNull() shouldBe (row[1].toDouble() plusOrMinus 1e-3)
                sample.offsetM.shouldNotBeNull() shouldBe (row[2].toDouble() plusOrMinus 1e-3)
                sample.dHeightM.shouldNotBeNull() shouldBe (row[7].toDouble() plusOrMinus 1e-3)
                compared++
            }
        }
        compared shouldBe 42 // las 45 epocas comparadas menos las 3 que caen mas alla del final del eje
        positions.zip(expected).count { (_, row) -> row[8] == "fuera_del_eje" } shouldBe 3
        positions.zip(expected).count { (_, row) -> row[8] == "sin_proyeccion" } shouldBe 3
    }

    @Test
    fun `S052 RF-17 y RF-18 el cruce de la seccion del PK 105 da el error en PK la diferencia de cota y la marca a 1 mm`() {
        val eje = axis(2)
        val geometry = AlignmentGeometry(eje.elements)
        val design = CrossSection(
            SectionKind.DESIGN,
            SECTION_PK,
            HeightKind.ABSOLUTE,
            rows("seccion-diseno.csv").mapIndexed { i, r -> SectionPoint("D${i + 1}", r[0].toDouble(), r[1].toDouble()) },
        )
        val expected = rows("reproduccion-transversal.csv")
        val positions = harness.positions(File(directory, "reproduccion-transversal.nmea"), expected.size)

        positions.size shouldBe expected.size - 1
        for ((position, row) in positions.zip(expected)) {
            val tip = position.shouldNotBeNull()
            val sample = ProfileSampler.cross(
                geometry,
                null,
                design,
                SECTION_PK,
                tip.easting!!,
                tip.northing!!,
                tip.height,
                SECTION_TOLERANCE_M,
            )

            sample.flag shouldBe flagOf(row[9])
            (sample.chainageM.shouldNotBeNull() - SECTION_PK) shouldBe (row[6].toDouble() plusOrMinus 1e-3)
            sample.offsetM.shouldNotBeNull() shouldBe (row[2].toDouble() plusOrMinus 1e-3)
            sample.dHeightM.shouldNotBeNull() shouldBe (row[8].toDouble() plusOrMinus 1e-3)
        }
        positions.zip(expected).count { (_, row) -> row[9] == "fuera_de_seccion" } shouldBe 3
    }

    private companion object {
        const val AXIS_TOLERANCE_M = 0.50
        const val SECTION_TOLERANCE_M = 0.10
        const val SECTION_PK = 105.0
    }
}

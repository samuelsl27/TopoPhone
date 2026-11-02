package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.survey.linear.SurveyFlag
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** PK, desplazamiento, diferencia de cota y marcas de un perfil frente a `fixtures/ejes/toma/` (S052/RF-11, RF-12, RF-17, RF-18). */
class ProfileSamplerTest {
    private val axisA = AlignmentFixtures.alignment("Eje A")
    private val geometryA = AlignmentGeometry(axisA.elements)
    private val gradeA = VerticalProfileEval(axisA.profile!!)
    private val geometryC = AlignmentGeometry(AlignmentFixtures.alignment("Eje C").elements)

    private fun coordinates(file: String) = SurveyFixtures.rows("toma/$file", "ejes").associate {
        it[0] to Triple(it[1].toDouble(), it[2].toDouble(), it[3].toDouble())
    }

    private fun flagOf(name: String) = when (name) {
        "ninguna" -> SurveyFlag.NONE
        "fuera_del_eje" -> SurveyFlag.OUT_OF_AXIS
        "sin_proyeccion" -> SurveyFlag.NO_PROJECTION
        else -> SurveyFlag.OUT_OF_SECTION
    }

    @Test
    fun `S052 RF-11 el PK el desplazamiento y la diferencia con la rasante coinciden con tools a 1 mm`() {
        val points = coordinates("longitudinal-puntos.csv")
        val expected = SurveyFixtures.rows("toma/esperado-longitudinal.csv", "ejes")
        var projected = 0

        for (row in expected) {
            val (e, n, z) = points.getValue(row[0])
            val sample = ProfileSampler.longitudinal(geometryA, gradeA, e, n, z, 0.50)

            sample.flag shouldBe flagOf(row[5])
            if (row[1].isEmpty()) {
                sample.chainageM.shouldBeNull()
                sample.offsetM.shouldBeNull()
                sample.dHeightM.shouldBeNull()
            } else {
                sample.chainageM.shouldNotBeNull() shouldBe (row[1].toDouble() plusOrMinus 1e-3)
                sample.offsetM.shouldNotBeNull() shouldBe (row[2].toDouble() plusOrMinus 1e-3)
                sample.dHeightM.shouldNotBeNull() shouldBe (row[4].toDouble() plusOrMinus 1e-3)
                projected++
            }
        }
        projected shouldBe 10
    }

    @Test
    fun `S052 RF-12 la tolerancia de eje se puede cambiar y 0,45 m pasan con 0,50 pero no con 0,40`() {
        val (e, n, z) = coordinates("longitudinal-puntos.csv").getValue("L6")

        ProfileSampler.longitudinal(geometryA, gradeA, e, n, z, 0.50).flag shouldBe SurveyFlag.NONE
        ProfileSampler.longitudinal(geometryA, gradeA, e, n, z, 0.40).flag shouldBe SurveyFlag.OUT_OF_AXIS
    }

    @Test
    fun `S052 RF-11 sin rasante o sin cota no hay diferencia de cota pero si PK y desplazamiento`() {
        val (e, n, z) = coordinates("longitudinal-puntos.csv").getValue("L4")

        val noGrade = ProfileSampler.longitudinal(geometryA, null, e, n, z, 0.50)
        val noHeight = ProfileSampler.longitudinal(geometryA, gradeA, e, n, null, 0.50)

        noGrade.dHeightM.shouldBeNull()
        noGrade.chainageM.shouldNotBeNull() shouldBe (130.0 plusOrMinus 1e-3)
        noHeight.dHeightM.shouldBeNull()
        noHeight.flag shouldBe SurveyFlag.NONE
    }

    private val design = CrossSection(
        SectionKind.DESIGN,
        105.0,
        HeightKind.ABSOLUTE,
        SurveyFixtures.rows("toma/seccion-diseno.csv", "ejes").mapIndexed { i, r ->
            SectionPoint("D${i + 1}", r[0].toDouble(), r[1].toDouble())
        },
    )

    @Test
    fun `S052 RF-17 el error en PK el desplazamiento y la diferencia con la seccion coinciden con tools a 1 mm`() {
        val points = coordinates("transversal-puntos.csv")
        val expected = SurveyFixtures.rows("toma/esperado-transversal.csv", "ejes")
        var withDifference = 0

        for (row in expected) {
            val (e, n, z) = points.getValue(row[0])
            val sample = ProfileSampler.cross(geometryC, null, design, 105.0, e, n, z, 0.10)

            sample.flag shouldBe flagOf(row[4])
            (sample.chainageM.shouldNotBeNull() - 105.0) shouldBe (row[1].toDouble() plusOrMinus 1e-3)
            sample.offsetM.shouldNotBeNull() shouldBe (row[2].toDouble() plusOrMinus 1e-3)
            if (row[3].isEmpty()) {
                sample.dHeightM.shouldBeNull()
            } else {
                sample.dHeightM.shouldNotBeNull() shouldBe (row[3].toDouble() plusOrMinus 1e-3)
                withDifference++
            }
        }
        withDifference shouldBe 7
    }

    @Test
    fun `S052 RF-18 la tolerancia de seccion se puede cambiar y 0,15 m pasan con 0,20 pero no con 0,10`() {
        val (e, n, z) = coordinates("transversal-puntos.csv").getValue("X4")

        ProfileSampler.cross(geometryC, null, design, 105.0, e, n, z, 0.10).flag shouldBe SurveyFlag.OUT_OF_SECTION
        ProfileSampler.cross(geometryC, null, design, 105.0, e, n, z, 0.20).flag shouldBe SurveyFlag.NONE
    }

    @Test
    fun `S052 RF-17 sin seccion de diseno no hay diferencia de cota y sin proyeccion no hay PK`() {
        val (e, n, z) = coordinates("transversal-puntos.csv").getValue("X1")

        ProfileSampler.cross(geometryC, null, null, 105.0, e, n, z, 0.10).dHeightM.shouldBeNull()
        val outside = ProfileSampler.cross(geometryC, null, design, 105.0, 441000.0 - 50.0, 4474000.0, 100.0, 0.10)
        outside.flag shouldBe SurveyFlag.NO_PROJECTION
        outside.chainageM.shouldBeNull()
    }
}

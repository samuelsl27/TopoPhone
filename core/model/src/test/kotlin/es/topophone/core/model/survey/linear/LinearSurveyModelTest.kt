package es.topophone.core.model.survey.linear

import es.topophone.core.model.alignment.AlignmentSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos de las tomas lineales (S052/RF-8, RF-9). */
class LinearSurveyModelTest {
    private fun survey(finishedAtMs: Long?) = LinearSurvey(
        id = "s1",
        projectId = "p1",
        kind = LinearSurveyKind.LONGITUDINAL,
        alignmentId = "a1",
        name = "Perfil",
        createdAtMs = 1_000,
        finishedAtMs = finishedAtMs,
    )

    @Test
    fun `S052 RF-8 un eje tomado en campo tiene su propio origen`() {
        AlignmentSource.valueOf("FIELD") shouldBe AlignmentSource.FIELD
    }

    @Test
    fun `S052 RF-9 una toma sin fecha de fin esta abierta y se puede reanudar`() {
        survey(null).isOpen shouldBe true
        survey(2_000).isOpen shouldBe false
    }

    @Test
    fun `S052 RF-12 las tolerancias por omision son 0,50 m de eje y 0,10 m de seccion`() {
        LinearSurvey.DEFAULT_AXIS_TOLERANCE_M shouldBe 0.50
        LinearSurvey.DEFAULT_SECTION_TOLERANCE_M shouldBe 0.10
        survey(null).toleranceM shouldBe null
    }

    @Test
    fun `S052 RF-12 un punto de la toma entra en el perfil solo si esta incluido y sin marca`() {
        SurveyPoint("a", 0, true, 10.0, 0.1, null, SurveyFlag.NONE).inProfile shouldBe true
        SurveyPoint("a", 0, false, 10.0, 0.1, null, SurveyFlag.NONE).inProfile shouldBe false
        SurveyPoint("a", 0, true, 10.0, 0.9, null, SurveyFlag.OUT_OF_AXIS).inProfile shouldBe false
        SurveyPoint("a", 0, true, null, null, null, SurveyFlag.NO_PROJECTION).inProfile shouldBe false
        SurveyPoint("a", 0, true, 10.0, 0.0, null, SurveyFlag.OUT_OF_SECTION).inProfile shouldBe false
    }
}

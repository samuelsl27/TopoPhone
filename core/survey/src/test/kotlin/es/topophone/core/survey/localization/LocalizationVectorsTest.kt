package es.topophone.core.survey.localization

import es.topophone.core.model.localization.VerticalKind
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** La localización frente a un cálculo independiente (S039/RF-7 a RF-9; constitución nº 2: 0,1 mm y 1e-9). */
class LocalizationVectorsTest {

    @Test
    fun `S039 RF-9 los parametros y los residuos coinciden con el calculo independiente a 0,1 mm y 1e-9`() {
        LocalizationFixtures.cases.size shouldBeGreaterThan 4

        val failures = mutableListOf<String>()
        for (case in LocalizationFixtures.cases) {
            val solution = LocalizationSolver.solve(case.pairs, case.fixScale).shouldBeInstanceOf<LocalizationOutcome.Solved>().solution
            val p = solution.params
            val e = case.expected

            fun check(label: String, actual: Double, expected: Double, tolerance: Double) {
                if (abs(actual - expected) > tolerance) failures += "${case.name} $label: $actual frente a $expected"
            }
            check("centro E", p.centreE, e.getValue("c_e").toDouble(), 1e-4)
            check("centro N", p.centreN, e.getValue("c_n").toDouble(), 1e-4)
            check("conocido E", p.knownCentreE, e.getValue("c_e2").toDouble(), 1e-4)
            check("conocido N", p.knownCentreN, e.getValue("c_n2").toDouble(), 1e-4)
            check("giro", p.rotationRad, e.getValue("giro_rad").toDouble(), 1e-9)
            check("escala", p.scale, e.getValue("escala").toDouble(), 1e-9)
            p.verticalKind shouldBe VerticalKind.valueOf(e.getValue("tipo_vertical"))
            check("dh", p.dh, e.getValue("dh").toDouble(), 1e-4)
            check("pendiente E", p.slopeE, e.getValue("pend_e").toDouble(), 1e-9)
            check("pendiente N", p.slopeN, e.getValue("pend_n").toDouble(), 1e-9)
            check("RMS H", solution.rmsHorizontal ?: 0.0, e.getValue("rms_h").toDouble(), 1e-4)
            check("RMS V", solution.rmsVertical ?: 0.0, e.getValue("rms_v").toDouble(), 1e-4)
            solution.residuals.zip(case.residuals).forEachIndexed { i, (actual, expected) ->
                check("res E $i", actual.east ?: 0.0, expected.first, 1e-4)
                check("res N $i", actual.north ?: 0.0, expected.second, 1e-4)
                check("res V $i", actual.vertical ?: 0.0, expected.third, 1e-4)
            }
            val worst = e.getValue("mayor_residuo_punto")
            val worstId = "${case.name}-$worst"
            if (solution.worstPairId != worstId) failures += "${case.name} mayor residuo: ${solution.worstPairId} frente a $worst"
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S039 RF-9 sin ruido recupera la transformacion con residuos menores que 0,1 mm`() {
        val case = LocalizationFixtures.cases.first { it.name == "c1_utm30_sin_ruido" }

        val solution = LocalizationSolver.solve(case.pairs, fixScale = false).shouldBeInstanceOf<LocalizationOutcome.Solved>().solution

        solution.residuals.forEach {
            ((it.horizontal ?: 0.0) < 1e-4) shouldBe true
            (abs(it.vertical ?: 0.0) < 1e-4) shouldBe true
        }
        (abs(solution.params.rotationRad - 0.0035) < 1e-9) shouldBe true
        (abs(solution.params.scale - 1.00002) < 1e-9) shouldBe true
    }
}

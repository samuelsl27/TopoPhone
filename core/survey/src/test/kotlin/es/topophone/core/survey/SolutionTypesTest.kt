package es.topophone.core.survey

import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.SolutionType
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El tipo de solución de una época según la calidad de su GGA (S047/RF-18, términos). */
class SolutionTypesTest {

    @Test
    fun `S047 RF-18 FIX FLOAT y SINGLE son ellos mismos`() {
        SolutionTypes.of(SolutionStatus.FIX) shouldBe SolutionType.FIX
        SolutionTypes.of(SolutionStatus.FLOAT) shouldBe SolutionType.FLOAT
        SolutionTypes.of(SolutionStatus.SINGLE) shouldBe SolutionType.SINGLE
    }

    @Test
    fun `S047 RF-18 una calidad DGPS cuenta como SINGLE`() {
        SolutionTypes.of(SolutionStatus.DGPS) shouldBe SolutionType.SINGLE
        SolutionTypes.of(SolutionStatus.fromGgaQuality(2)) shouldBe SolutionType.SINGLE
    }

    @Test
    fun `S047 una epoca sin posicion valida o estimada no tiene tipo y nunca se admite`() {
        SolutionTypes.of(SolutionStatus.NONE).shouldBeNull()
        SolutionTypes.of(SolutionStatus.ESTIMATED).shouldBeNull()
    }
}

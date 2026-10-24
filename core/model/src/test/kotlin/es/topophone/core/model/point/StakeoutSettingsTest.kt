package es.topophone.core.model.point

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los ajustes del replanteo: modo de guía, origen del rumbo y tolerancia (S018/RF-5, RF-8, RF-18). */
class StakeoutSettingsTest {

    @Test
    fun `S018 RF-5 y RF-8 por defecto guia por direccion con el rumbo de marcha y 2,5 cm`() {
        val settings = StakeoutSettings()

        settings.mode shouldBe StakeMode.BY_DIRECTION
        settings.heading shouldBe HeadingChoice.WALKING
        settings.toleranceM shouldBe 0.025
    }

    @Test
    fun `S018 RF-18 la tolerancia vale de 1 cm a 1 m y fuera de ese rango se corrige`() {
        StakeoutSettings(toleranceM = 0.001).validToleranceM shouldBe 0.01
        StakeoutSettings(toleranceM = 5.0).validToleranceM shouldBe 1.0
        StakeoutSettings(toleranceM = 0.04).validToleranceM shouldBe 0.04
        StakeoutSettings(toleranceM = Double.NaN).validToleranceM shouldBe 0.025
    }

    @Test
    fun `S018 RF-18 la tolerancia se escribe en la unidad del proyecto y se guarda en metros`() {
        StakeoutSettings.parseTolerance("0,05", LinearUnit.METRE) shouldBe 0.05
        StakeoutSettings.parseTolerance("0,1", LinearUnit.INTERNATIONAL_FOOT).shouldNotBeNull() shouldBe (0.03048 plusOrMinus 1e-12)
        StakeoutSettings.parseTolerance("1", LinearUnit.US_SURVEY_FOOT).shouldNotBeNull() shouldBe (1200.0 / 3937.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S018 RF-18 una tolerancia fuera de rango o que no es un numero no se acepta`() {
        StakeoutSettings.parseTolerance("0,005", LinearUnit.METRE).shouldBeNull()
        StakeoutSettings.parseTolerance("2", LinearUnit.METRE).shouldBeNull()
        StakeoutSettings.parseTolerance("abc", LinearUnit.METRE).shouldBeNull()
        StakeoutSettings.parseTolerance("", LinearUnit.METRE).shouldBeNull()
    }

    @Test
    fun `S018 RF-18 el redondeo a pies de los limites se perdona hasta 1 mm`() {
        // 0,033 ft son 0,01006 m y 0,03 ft, 0,0091 m: ese queda por debajo del mínimo pero dentro del margen.
        StakeoutSettings.parseTolerance("0,033", LinearUnit.INTERNATIONAL_FOOT).shouldNotBeNull()
        StakeoutSettings.parseTolerance("0,03", LinearUnit.INTERNATIONAL_FOOT) shouldBe 0.01
        StakeoutSettings.parseTolerance("3,281", LinearUnit.INTERNATIONAL_FOOT).shouldNotBeNull()
        StakeoutSettings.parseTolerance("3,3", LinearUnit.INTERNATIONAL_FOOT).shouldBeNull()
    }

    @Test
    fun `S019 RF-10 la tolerancia vertical es de 3 cm por defecto`() {
        StakeoutSettings().toleranceVM shouldBe 0.03
        StakeoutSettings.DEFAULT_TOLERANCE_V_M shouldBe 0.03
    }

    @Test
    fun `S019 RF-10 la tolerancia vertical vale de 1 cm a 1 m y fuera de ese rango se corrige`() {
        StakeoutSettings(toleranceVM = 0.001).validToleranceVM shouldBe 0.01
        StakeoutSettings(toleranceVM = 5.0).validToleranceVM shouldBe 1.0
        StakeoutSettings(toleranceVM = 0.04).validToleranceVM shouldBe 0.04
        StakeoutSettings(toleranceVM = Double.NaN).validToleranceVM shouldBe 0.03
    }

    @Test
    fun `S019 RF-10 las dos tolerancias son independientes`() {
        val settings = StakeoutSettings(toleranceM = 0.05, toleranceVM = 0.02)

        settings.validToleranceM shouldBe 0.05
        settings.validToleranceVM shouldBe 0.02
        settings.copy(toleranceM = 0.1).toleranceVM shouldBe 0.02
    }

    @Test
    fun `S019 RF-10 la tolerancia vertical se escribe en la unidad del proyecto y se guarda en metros`() {
        // Es el mismo rango y la misma conversión que la horizontal: 1 cm a 1 m.
        StakeoutSettings.parseTolerance("0,03", LinearUnit.METRE) shouldBe 0.03
        StakeoutSettings.parseTolerance("0,1", LinearUnit.INTERNATIONAL_FOOT).shouldNotBeNull() shouldBe (0.03048 plusOrMinus 1e-12)
        StakeoutSettings.parseTolerance("0,005", LinearUnit.METRE).shouldBeNull()
    }

    @Test
    fun `S018 RF-18 los limites se ensenan en la unidad del proyecto`() {
        val feet = StakeoutSettings.toleranceRangeIn(LinearUnit.INTERNATIONAL_FOOT)

        feet.start shouldBe (0.01 / 0.3048 plusOrMinus 1e-9)
        feet.endInclusive shouldBe (1.0 / 0.3048 plusOrMinus 1e-9)
    }
}

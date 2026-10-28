package es.topophone.core.survey

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Los valores escritos del replanteo por PK y el motivo por el que no se aplican (S054/RF-1, RF-5, RF-9). */
class AxisStakeInputsTest {
    private val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje A")) // PK 0 a 420

    private fun <T> AxisCheck<T>.value(): T = (this as AxisCheck.Valid<T>).value

    @Test
    fun `S054 RF-1 el PK se escribe en kilometros y metros o suelto y con coma o punto`() {
        AxisStakeInputs.chainage("150", LinearUnit.METRE, geometry).value() shouldBe 150.0
        AxisStakeInputs.chainage("0+150,5", LinearUnit.METRE, geometry).value() shouldBe (150.5 plusOrMinus 1e-12)
        AxisStakeInputs.chainage("0+150.5", LinearUnit.METRE, geometry).value() shouldBe (150.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S054 RF-9 un PK que no es numero o cae fuera del eje no se aplica`() {
        AxisStakeInputs.chainage("", LinearUnit.METRE, geometry) shouldBe AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)
        AxisStakeInputs.chainage("abc", LinearUnit.METRE, geometry) shouldBe AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)
        AxisStakeInputs.chainage("421", LinearUnit.METRE, geometry) shouldBe AxisCheck.Invalid(AxisInputError.OUT_OF_AXIS)
        AxisStakeInputs.chainage("-1", LinearUnit.METRE, geometry) shouldBe AxisCheck.Invalid(AxisInputError.OUT_OF_AXIS)
        AxisStakeInputs.chainage("420", LinearUnit.METRE, geometry).shouldBeInstanceOf<AxisCheck.Valid<Double>>()
    }

    @Test
    fun `S054 RF-1 el PK final es opcional y si se escribe tiene que caber en el eje`() {
        AxisStakeInputs.optionalChainage("", LinearUnit.METRE, geometry).value() shouldBe null
        AxisStakeInputs.optionalChainage("300", LinearUnit.METRE, geometry).value() shouldBe 300.0
        AxisStakeInputs.optionalChainage("500", LinearUnit.METRE, geometry) shouldBe AxisCheck.Invalid(AxisInputError.OUT_OF_AXIS)
    }

    @Test
    fun `S054 RF-1 el desplazamiento y el espesor valen 0 vacios y aceptan signo y la unidad del proyecto`() {
        AxisStakeInputs.offset("", LinearUnit.METRE).value() shouldBe 0.0
        AxisStakeInputs.offset("-3,5", LinearUnit.METRE).value() shouldBe -3.5
        AxisStakeInputs.offset("10", LinearUnit.INTERNATIONAL_FOOT).value() shouldBe (3.048 plusOrMinus 1e-12)
        AxisStakeInputs.thickness("", LinearUnit.METRE).value() shouldBe 0.0
        AxisStakeInputs.thickness("-0.25", LinearUnit.METRE).value() shouldBe -0.25
        AxisStakeInputs.offset("x", LinearUnit.METRE) shouldBe AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)
        AxisStakeInputs.thickness("NaN", LinearUnit.METRE) shouldBe AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)
    }

    @Test
    fun `S054 RF-2 sin intervalo no hay serie y con intervalo tiene que ser mayor que 0`() {
        AxisStakeInputs.interval("", LinearUnit.METRE).value() shouldBe null
        AxisStakeInputs.interval("20", LinearUnit.METRE).value() shouldBe 20.0
        AxisStakeInputs.interval("0", LinearUnit.METRE) shouldBe AxisCheck.Invalid(AxisInputError.INTERVAL_NOT_POSITIVE)
        AxisStakeInputs.interval("-5", LinearUnit.METRE) shouldBe AxisCheck.Invalid(AxisInputError.INTERVAL_NOT_POSITIVE)
        AxisStakeInputs.interval("a", LinearUnit.METRE) shouldBe AxisCheck.Invalid(AxisInputError.NOT_A_NUMBER)
    }
}

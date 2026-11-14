package es.topophone.core.model.format

import es.topophone.core.model.project.AngularUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Azimuts, ángulos y deflexiones escritos en las unidades del proyecto (S038/RF-4). */
class DirectionInputTest {

    private val tolerance = 1e-12

    private fun rad(deg: Double) = Math.toRadians(deg)

    @Test
    fun `S038 RF-4 grados decimales con punto o coma`() {
        DirectionInput.parse("45", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH)!! shouldBe (rad(45.0) plusOrMinus tolerance)
        DirectionInput.parse("123,4567", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH)!! shouldBe
            (rad(123.4567) plusOrMinus tolerance)
        DirectionInput.parse("123.4567°", AngularUnit.DECIMAL_DEGREES, DirectionKind.ANGLE)!! shouldBe (rad(123.4567) plusOrMinus tolerance)
        DirectionInput.parse("0", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH) shouldBe 0.0
    }

    @Test
    fun `S038 RF-4 grados minutos y segundos con simbolos o con espacios`() {
        val expected = rad(40.0 + 25.0 / 60 + 7.24 / 3600)
        DirectionInput.parse("40 25 7,24", AngularUnit.DMS, DirectionKind.AZIMUTH)!! shouldBe (expected plusOrMinus tolerance)
        DirectionInput.parse("40°25'7.24\"", AngularUnit.DMS, DirectionKind.AZIMUTH)!! shouldBe (expected plusOrMinus tolerance)
        DirectionInput.parse("40 30", AngularUnit.DMS, DirectionKind.ANGLE)!! shouldBe (rad(40.5) plusOrMinus tolerance)
    }

    @Test
    fun `S038 RF-4 gon`() {
        DirectionInput.parse("100", AngularUnit.GON, DirectionKind.AZIMUTH)!! shouldBe (Math.PI / 2 plusOrMinus tolerance)
        DirectionInput.parse("266,6667 gon", AngularUnit.GON, DirectionKind.ANGLE)!! shouldBe (rad(266.6667 * 0.9) plusOrMinus tolerance)
        DirectionInput.parse("200", AngularUnit.GON, DirectionKind.DEFLECTION)!! shouldBe (Math.PI plusOrMinus tolerance)
    }

    @Test
    fun `S038 RF-4 un azimut o un angulo de 360 grados o de 400 gon no vale`() {
        DirectionInput.parse("360", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("360 0 0", AngularUnit.DMS, DirectionKind.ANGLE).shouldBeNull()
        DirectionInput.parse("400", AngularUnit.GON, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("359,999999", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH)!! shouldBe
            (rad(359.999999) plusOrMinus tolerance)
        DirectionInput.parse("399,9999", AngularUnit.GON, DirectionKind.ANGLE)!! shouldBe (rad(399.9999 * 0.9) plusOrMinus tolerance)
    }

    @Test
    fun `S038 RF-4 un azimut negativo no vale`() {
        DirectionInput.parse("-1", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("-0,5", AngularUnit.GON, DirectionKind.ANGLE).shouldBeNull()
        DirectionInput.parse("+10", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH).shouldBeNull()
    }

    @Test
    fun `S038 RF-4 la deflexion llega a 180 grados y no pasa`() {
        DirectionInput.parse("180", AngularUnit.DECIMAL_DEGREES, DirectionKind.DEFLECTION)!! shouldBe (Math.PI plusOrMinus tolerance)
        DirectionInput.parse("180,0001", AngularUnit.DECIMAL_DEGREES, DirectionKind.DEFLECTION).shouldBeNull()
        DirectionInput.parse("200,01", AngularUnit.GON, DirectionKind.DEFLECTION).shouldBeNull()
        DirectionInput.parse("90", AngularUnit.DECIMAL_DEGREES, DirectionKind.DEFLECTION)!! shouldBe (Math.PI / 2 plusOrMinus tolerance)
    }

    @Test
    fun `S038 RF-4 lo que no es un numero no vale`() {
        DirectionInput.parse("", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("abc", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("12 N", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("45 61", AngularUnit.DMS, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("1 2 3 4", AngularUnit.DMS, DirectionKind.AZIMUTH).shouldBeNull()
        DirectionInput.parse("1e2", AngularUnit.DECIMAL_DEGREES, DirectionKind.AZIMUTH).shouldBeNull()
    }
}

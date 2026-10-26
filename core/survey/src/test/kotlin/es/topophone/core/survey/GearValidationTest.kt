package es.topophone.core.survey

import es.topophone.core.model.point.PoleKind
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Nombres únicos y rangos de perfiles de antena y jalones (S046/RF-3, RF-4, RF-9, RF-10). */
class GearValidationTest {

    private fun profile(name: String, offset: String, radius: String = "", existing: List<String> = emptyList(), ignoring: String? = null) =
        GearValidation.profile(name, offset, radius, existing, ignoring)

    private fun pole(name: String, height: String, existing: List<String> = emptyList(), ignoring: String? = null) =
        GearValidation.pole(name, PoleKind.VERTICAL, height, existing, ignoring)

    @Test
    fun `S046 RF-3 un perfil valido lleva nombre desplazamiento y radio opcional`() {
        profile("Mi receptor", "0,120", "0.06") shouldBe ProfileCheck.Valid("Mi receptor", 0.12, 0.06)
        profile("  Sin radio ", "0.095") shouldBe ProfileCheck.Valid("Sin radio", 0.095, null)
    }

    @Test
    fun `S046 RF-3 RF-4 el nombre vacio, largo o repetido sin mayusculas ni tildes no vale`() {
        profile("   ", "0.1") shouldBe ProfileCheck.Invalid(GearProblemField.NAME_EMPTY)
        profile("a".repeat(41), "0.1") shouldBe ProfileCheck.Invalid(GearProblemField.NAME_TOO_LONG)
        profile("a".repeat(40), "0.1") shouldBe ProfileCheck.Valid("a".repeat(40), 0.1, null)
        profile("RECEPTOR Ávila", "0.1", existing = listOf("receptor avila")) shouldBe ProfileCheck.Invalid(GearProblemField.NAME_TAKEN)
    }

    @Test
    fun `S046 RF-5 al editar un perfil su propio nombre no cuenta como repetido`() {
        profile("Mi receptor", "0.1", existing = listOf("Mi receptor", "Otro"), ignoring = "Mi receptor") shouldBe
            ProfileCheck.Valid("Mi receptor", 0.1, null)
        profile("Otro", "0.1", existing = listOf("Mi receptor", "Otro"), ignoring = "Mi receptor") shouldBe
            ProfileCheck.Invalid(GearProblemField.NAME_TAKEN)
    }

    @Test
    fun `S046 RF-4 el desplazamiento va de 0 a 1 m y el radio de 0 a 0,3 m`() {
        profile("P", "0") shouldBe ProfileCheck.Valid("P", 0.0, null)
        profile("P", "1") shouldBe ProfileCheck.Valid("P", 1.0, null)
        profile("P", "1,001") shouldBe ProfileCheck.Invalid(GearProblemField.OFFSET)
        profile("P", "-0.1") shouldBe ProfileCheck.Invalid(GearProblemField.OFFSET)
        profile("P", "abc") shouldBe ProfileCheck.Invalid(GearProblemField.OFFSET)
        profile("P", "") shouldBe ProfileCheck.Invalid(GearProblemField.OFFSET)
        profile("P", "0.1", "0.3") shouldBe ProfileCheck.Valid("P", 0.1, 0.3)
        profile("P", "0.1", "0.301") shouldBe ProfileCheck.Invalid(GearProblemField.RADIUS)
        profile("P", "0.1", "x") shouldBe ProfileCheck.Invalid(GearProblemField.RADIUS)
    }

    @Test
    fun `S046 RF-9 RF-10 un jalon lleva nombre unico de hasta 30 caracteres y altura de 0 a 10 m con coma o punto`() {
        pole("Jalón corto", "1,5") shouldBe PoleCheck.Valid("Jalón corto", PoleKind.VERTICAL, 1.5)
        pole("Cero", "0") shouldBe PoleCheck.Valid("Cero", PoleKind.VERTICAL, 0.0)
        pole("Diez", "10") shouldBe PoleCheck.Valid("Diez", PoleKind.VERTICAL, 10.0)
        pole("Once", "10,001") shouldBe PoleCheck.Invalid(GearProblemField.HEIGHT)
        pole("Letras", "dos") shouldBe PoleCheck.Invalid(GearProblemField.HEIGHT)
        pole("Neg", "-1") shouldBe PoleCheck.Invalid(GearProblemField.HEIGHT)
        pole("", "1.8") shouldBe PoleCheck.Invalid(GearProblemField.NAME_EMPTY)
        pole("n".repeat(31), "1.8") shouldBe PoleCheck.Invalid(GearProblemField.NAME_TOO_LONG)
        pole("Jalón ESTÁNDAR", "1.8", existing = listOf("jalon estandar")) shouldBe PoleCheck.Invalid(GearProblemField.NAME_TAKEN)
    }

    @Test
    fun `S046 RF-16 el tipo de medida de un jalon se conserva`() {
        GearValidation.pole("Inclinado", PoleKind.SLANT, "1.95", emptyList(), null) shouldBe
            PoleCheck.Valid("Inclinado", PoleKind.SLANT, 1.95)
    }
}

package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/** Altura vertical del jalón y altura de antena (S046/RF-16 a RF-20), frente a `fixtures/antena/` calculado con Python. */
class PoleGeometryTest {

    private fun valid(height: VerticalHeight): Double = (height as VerticalHeight.Valid).metres

    @Test
    fun `S046 RF-16 un jalon vertical da su altura escrita`() {
        valid(PoleGeometry.vertical(PoleKind.VERTICAL, 1.8, null)) shouldBe 1.8
        valid(PoleGeometry.vertical(PoleKind.VERTICAL, 0.0, 0.06)) shouldBe 0.0
        valid(PoleGeometry.vertical(PoleKind.VERTICAL, 10.0, null)) shouldBe 10.0
    }

    @Test
    fun `S046 RF-17 un jalon inclinado con radio da la raiz de la inclinada menos el radio al cuadrado`() {
        val v = valid(PoleGeometry.vertical(PoleKind.SLANT, 1.95, 0.06))
        abs(v - sqrt(1.95 * 1.95 - 0.06 * 0.06)) shouldBe 0.0.plusOrMinus(1e-4)
        (abs(v - 1.949076) < 1e-6) shouldBe true
    }

    @Test
    fun `S046 RF-18 sin radio la medida inclinada no vale`() {
        PoleGeometry.vertical(PoleKind.SLANT, 1.95, null) shouldBe VerticalHeight.Invalid(GearProblem.NO_RADIUS)
    }

    @Test
    fun `S046 RF-18 con la inclinada menor que el radio no vale y con la igual da cero`() {
        PoleGeometry.vertical(PoleKind.SLANT, 0.05, 0.06) shouldBe VerticalHeight.Invalid(GearProblem.SLANT_SHORTER_THAN_RADIUS)
        valid(PoleGeometry.vertical(PoleKind.SLANT, 0.06, 0.06)) shouldBe 0.0
    }

    @Test
    fun `S046 RF-20 la altura de antena es la vertical mas el desplazamiento del perfil`() {
        PoleGeometry.antennaHeightM(1.8, AntennaProfile.RS3.offsetM) shouldBe 1.934.plusOrMinus(1e-12)
        PoleGeometry.antennaHeightM(0.0, 0.095) shouldBe 0.095
    }

    @Test
    fun `S046 RF-17 RF-18 RF-20 coincide con el fixture calculado aparte (0,1 mm)`() {
        val rows = SurveyFixtures.rows("punta_perfiles.csv", "antena")
        (rows.size > 100) shouldBe true
        rows.forEach { row ->
            val kind = PoleKind.valueOf(row[2])
            val entered = row[3].toDouble()
            val radius = row[4].takeIf { it.isNotEmpty() }?.toDouble()
            val result = PoleGeometry.vertical(kind, entered, radius)
            when (row[5]) {
                "OK" -> {
                    abs(valid(result) - row[6].toDouble()) shouldBe 0.0.plusOrMinus(1e-4)
                    abs(PoleGeometry.antennaHeightM(valid(result), row[1].toDouble()) - row[7].toDouble()) shouldBe 0.0.plusOrMinus(1e-4)
                }
                "NO_RADIUS" -> result shouldBe VerticalHeight.Invalid(GearProblem.NO_RADIUS)
                else -> result shouldBe VerticalHeight.Invalid(GearProblem.SLANT_SHORTER_THAN_RADIUS)
            }
        }
    }
}

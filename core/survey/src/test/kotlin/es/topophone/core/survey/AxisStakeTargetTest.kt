package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** El punto de diseño por PK, desplazamiento y espesor frente a `fixtures/ejes/replanteo/` (S054/RF-1, RF-7, RF-9; constitución nº 2). */
class AxisStakeTargetTest {
    private val rows = SurveyFixtures.rows("esperado-objetivos.csv", "ejes/replanteo")

    private fun target(axis: String, withProfile: Boolean = true, pk: Double, offset: Double, thickness: Double = 0.0): AxisStakeResult {
        val alignment = AlignmentFixtures.alignment(axis, withProfile)
        return AxisStakeTarget.of(AlignmentGeometry(alignment), alignment.profile?.let { VerticalProfileEval(it) }, pk, offset, thickness)
    }

    @Test
    fun `S054 RF-1 cada PK desplazamiento y espesor da el Este el Norte y el azimut esperados a menos de 1 mm`() {
        var checked = 0
        for (r in rows) {
            val result = target(r[0], pk = r[1].toDouble(), offset = r[2].toDouble(), thickness = r[3].toDouble())
            val got = result.shouldBeInstanceOf<AxisStakeResult.Target>()
            hypot(got.point.easting - r[4].toDouble(), got.point.northing - r[5].toDouble()) shouldBe (0.0 plusOrMinus 1e-6)
            Math.toDegrees(got.point.azimuth) shouldBe (r[6].toDouble() plusOrMinus 1e-9)
            checked++
        }
        (checked > 300) shouldBe true
    }

    @Test
    fun `S054 RF-7 la cota de diseno es la rasante en el PK mas el espesor`() {
        var withElevation = 0
        for (r in rows.filter { it[0] != "Eje C" }) {
            val got = target(r[0], pk = r[1].toDouble(), offset = r[2].toDouble(), thickness = r[3].toDouble())
                .shouldBeInstanceOf<AxisStakeResult.Target>()
            got.elevationM.shouldNotBeNull() shouldBe (r[7].toDouble() plusOrMinus 1e-6)
            withElevation++
        }
        (withElevation > 200) shouldBe true
    }

    @Test
    fun `S054 RF-7 un espesor negativo baja la cota y el espesor no cambia la planta`() {
        val plain = target("Eje A", pk = 200.0, offset = 3.5).shouldBeInstanceOf<AxisStakeResult.Target>()
        val thick = target("Eje A", pk = 200.0, offset = 3.5, thickness = -0.25).shouldBeInstanceOf<AxisStakeResult.Target>()

        thick.elevationM.shouldNotBeNull() shouldBe (plain.elevationM.shouldNotBeNull() - 0.25 plusOrMinus 1e-12)
        thick.profileElevationM shouldBe plain.profileElevationM
        thick.point shouldBe plain.point
    }

    @Test
    fun `S054 RF-7 un eje sin rasante se replantea solo en planta y sin cota`() {
        for (r in rows.filter { it[0] == "Eje C" }.take(5)) {
            val got = target("Eje C", pk = r[1].toDouble(), offset = r[2].toDouble(), thickness = r[3].toDouble())
                .shouldBeInstanceOf<AxisStakeResult.Target>()
            got.elevationM.shouldBeNull()
            got.profileElevationM.shouldBeNull()
        }
        val noProfile = target("Eje A", withProfile = false, pk = 100.0, offset = 0.0)
        noProfile.shouldBeInstanceOf<AxisStakeResult.Target>().elevationM.shouldBeNull()
    }

    @Test
    fun `S054 RF-9 un PK fuera del eje no da punto y dice el PK inicial y el final`() {
        target("Eje A", pk = -0.001, offset = 0.0) shouldBe AxisStakeResult.OutsideAxis(0.0, 420.0)
        val after = target("Eje A", pk = 420.001, offset = 3.5).shouldBeInstanceOf<AxisStakeResult.OutsideAxis>()
        after.startM shouldBe 0.0
        after.endM shouldBe (420.0 plusOrMinus 1e-9)
        val before = target("Eje B", pk = 1000.0, offset = 0.0).shouldBeInstanceOf<AxisStakeResult.OutsideAxis>()
        before.startM shouldBe 1000.5
        target("Eje A", pk = 420.0, offset = 0.0).shouldBeInstanceOf<AxisStakeResult.Target>()
    }

    @Test
    fun `S054 RF-1 un desplazamiento 0 esta sobre el eje`() {
        val got = target("Eje A", pk = 150.0, offset = 0.0).shouldBeInstanceOf<AxisStakeResult.Target>()

        got.point.easting shouldBe got.point.axisEasting
        got.point.northing shouldBe got.point.axisNorthing
    }
}

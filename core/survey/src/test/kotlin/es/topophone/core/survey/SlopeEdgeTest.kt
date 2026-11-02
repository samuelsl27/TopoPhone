package es.topophone.core.survey

import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.slope.ManualEdge
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El borde de explanación de un PK y un lado: el de la sección de S050, el de un código o el manual (S051/RF-1, RF-2, RF-5, RF-8). */
class SlopeEdgeTest {
    private val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje A").elements)
    private val profile = AlignmentFixtures.profileOf("Eje A")?.let { VerticalProfileEval(it) }
    private val resolver =
        SectionResolver(listOfNotNull(SectionFixtures.template("Eje A")), SectionFixtures.superelevation("Eje A"), emptyList())

    private fun rule(
        side: SlopeSide = SlopeSide.RIGHT,
        code: String? = null,
        manual: ManualEdge? = null,
        from: Double = 0.0,
        to: Double = 420.0,
    ) = SlopeRule(side, from, to, 1.5, 2.0, code, manual)

    private fun edge(
        rule: SlopeRule,
        pk: Double = 230.0,
        resolver: SectionResolver? = this.resolver,
        profile: VerticalProfileEval? = this.profile,
    ) = SlopeEdge.of(listOf(rule), rule.side, pk, geometry, profile, resolver)

    private fun expected(code: String) = SectionFixtures.points().first { it.axis == "Eje A" && it.chainage == 230.0 && it.code == code }

    @Test
    fun `S051 RF-5 por defecto el borde es el punto exterior de la seccion de cada lado`() {
        val right = edge(rule()).shouldBeInstanceOf<SlopeEdgeResult.Edge>()
        val left = edge(rule(SlopeSide.LEFT)).shouldBeInstanceOf<SlopeEdgeResult.Edge>()

        right.offsetM shouldBe (expected("CD").offset plusOrMinus 1e-9)
        right.heightM shouldBe (expected("CD").elevation!! plusOrMinus 1e-6)
        right.point.easting shouldBe (expected("CD").easting plusOrMinus 1e-6)
        right.point.northing shouldBe (expected("CD").northing plusOrMinus 1e-6)
        left.offsetM shouldBe (expected("CI").offset plusOrMinus 1e-9)
        left.heightM shouldBe (expected("CI").elevation!! plusOrMinus 1e-6)
    }

    @Test
    fun `S051 RF-1 un codigo elegido da el borde de ese punto de la seccion`() {
        val e = edge(rule(code = "BD")).shouldBeInstanceOf<SlopeEdgeResult.Edge>()

        e.offsetM shouldBe (expected("BD").offset plusOrMinus 1e-9)
        e.heightM shouldBe (expected("BD").elevation!! plusOrMinus 1e-6)
    }

    @Test
    fun `S051 RF-1 un codigo que no existe en la seccion o que esta en el otro lado no vale`() {
        edge(rule(code = "ZZ")) shouldBe SlopeEdgeResult.NoEdge("ZZ")
        edge(rule(code = "BI")) shouldBe SlopeEdgeResult.NoEdge("BI")
    }

    @Test
    fun `S051 RF-2 el borde manual manda sobre la seccion y su cota absoluta es la escrita`() {
        val e = edge(rule(manual = ManualEdge(4.0, 99.0, HeightKind.ABSOLUTE))).shouldBeInstanceOf<SlopeEdgeResult.Edge>()

        e.offsetM shouldBe 4.0
        e.heightM shouldBe 99.0
    }

    @Test
    fun `S051 RF-2 sin seccion el borde manual relativo suma la rasante del PK`() {
        val e = edge(rule(manual = ManualEdge(4.0, -0.5, HeightKind.RELATIVE)), resolver = null).shouldBeInstanceOf<SlopeEdgeResult.Edge>()

        e.heightM shouldBe (profile!!.at(230.0)!!.elevation - 0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `S051 RF-2 un borde manual relativo sin rasante no tiene cota`() {
        edge(rule(manual = ManualEdge(4.0, -0.5, HeightKind.RELATIVE)), resolver = null, profile = null) shouldBe SlopeEdgeResult.NoHeight
    }

    @Test
    fun `S051 RF-8 una seccion relativa sin rasante no tiene cota`() {
        edge(rule(), profile = null) shouldBe SlopeEdgeResult.NoHeight
    }

    @Test
    fun `S051 RF-8 un PK fuera de los taludes o del eje o sin seccion se dice`() {
        edge(rule(from = 0.0, to = 100.0)) shouldBe SlopeEdgeResult.NoRule
        SlopeEdge.of(listOf(rule()), SlopeSide.LEFT, 230.0, geometry, profile, resolver) shouldBe SlopeEdgeResult.NoRule
        edge(rule(), resolver = null) shouldBe SlopeEdgeResult.NoSection
        edge(rule(from = 0.0, to = 99_999.0, manual = ManualEdge(4.0, 99.0, HeightKind.ABSOLUTE)), pk = 9_999.0) shouldBe
            SlopeEdgeResult.OutsideAxis
    }
}

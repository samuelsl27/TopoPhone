package es.topophone.core.survey

import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.surface.SurfaceHeight
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El PK, el desplazamiento y la diferencia de cota de la punta del jalón respecto al eje (S054/RF-12, RF-14; constitución nº 2). */
class AxisAnalysisTest {
    private val points = AlignmentFixtures.points().filter { it.axis == "Eje A" && it.elevation != null }

    private fun analyse(
        axis: String,
        east: Double,
        north: Double,
        height: Double?,
        ref: AxisHeightRef? = AxisHeightRef.PROFILE,
        surface: SurfaceHeight? = null,
    ): AxisAnalysisValue {
        val alignment = AlignmentFixtures.alignment(axis)
        return AxisAnalysis.of(
            AlignmentGeometry(alignment),
            alignment.profile?.let { VerticalProfileEval(it) },
            ref,
            surface,
            east,
            north,
            height,
        )
    }

    private fun projected(value: AxisAnalysisValue) = value.shouldBeInstanceOf<AxisAnalysisValue.Projected>()

    @Test
    fun `S054 RF-12 el PK y el desplazamiento de la punta coinciden con los esperados a menos de 1 mm`() {
        (points.size > 30) shouldBe true
        for (p in points) {
            val got = projected(analyse("Eje A", p.easting, p.northing, p.elevation!! + 0.12))

            got.chainageM shouldBe (p.chainage plusOrMinus 1e-4)
            got.offsetM shouldBe (p.offset plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S054 RF-12 la diferencia de cota con la rasante es la cota de la punta menos la de la rasante`() {
        for (p in points.filter { it.offset == 3.5 }) {
            val got = projected(analyse("Eje A", p.easting, p.northing, p.elevation!! + 0.12))

            got.heightRef shouldBe AxisHeightRef.PROFILE
            val diff = got.height.shouldBeInstanceOf<AxisHeightDiff.Diff>()
            diff.dHeightM.shouldNotBeNull() shouldBe (0.12 plusOrMinus 1e-4)
            diff.referenceM shouldBe (p.elevation!! plusOrMinus 1e-4)
        }
    }

    @Test
    fun `S054 RF-12 la diferencia de cota con la superficie es la cota de la punta menos la de la superficie`() {
        val got = projected(analyse("Eje A", 440100.0, 4474050.0, 101.0, AxisHeightRef.SURFACE, SurfaceHeight.Value(100.25)))

        got.heightRef shouldBe AxisHeightRef.SURFACE
        (got.height as AxisHeightDiff.Diff).dHeightM.shouldNotBeNull() shouldBe (0.75 plusOrMinus 1e-12)
        projected(analyse("Eje A", 440100.0, 4474050.0, 101.0, AxisHeightRef.SURFACE, SurfaceHeight.Outside))
            .height shouldBe AxisHeightDiff.OutsideSurface
        projected(analyse("Eje A", 440100.0, 4474050.0, 101.0, AxisHeightRef.SURFACE, SurfaceHeight.Loading))
            .height shouldBe AxisHeightDiff.LoadingSurface
    }

    @Test
    fun `S054 RF-12 sin referencia solo hay PK y desplazamiento`() {
        projected(analyse("Eje C", 441100.0, 4474010.0, 5.0, ref = null)).height shouldBe AxisHeightDiff.None
        // la rasante elegida en un eje que no la tiene
        projected(analyse("Eje C", 441100.0, 4474010.0, 5.0, AxisHeightRef.PROFILE)).height shouldBe AxisHeightDiff.None
        // la superficie elegida sin superficie activa
        projected(analyse("Eje C", 441100.0, 4474010.0, 5.0, AxisHeightRef.SURFACE)).height shouldBe AxisHeightDiff.None
    }

    @Test
    fun `S054 RF-12 una punta sin altura da PK y desplazamiento y una diferencia de cota vacia`() {
        val got = projected(analyse("Eje A", 440100.0, 4474050.0, null))

        (got.height as AxisHeightDiff.Diff).dHeightM.shouldBeNull()
        (got.height as AxisHeightDiff.Diff).referenceM.shouldNotBeNull()
    }

    @Test
    fun `S054 RF-14 antes del inicio o despues del final del eje no hay PK ni desplazamiento`() {
        analyse("Eje C", 440990.0, 4474000.0, 5.0) shouldBe AxisAnalysisValue.NoProjection // el eje C empieza en 441000
        analyse("Eje C", 441210.0, 4474003.0, 5.0) shouldBe AxisAnalysisValue.NoProjection // y acaba en 441200
        val inside = projected(analyse("Eje C", 441100.0, 4474010.0, 5.0))
        inside.chainageM shouldBe (100.0 plusOrMinus 1e-9)
        inside.offsetM shouldBe (-10.0 plusOrMinus 1e-9) // a la izquierda de ir al Este es el Norte
    }

    private fun resolver(axis: String) = SectionResolver(
        listOfNotNull(SectionFixtures.template(axis)),
        SectionFixtures.superelevation(axis),
        if (axis == "Eje C") SectionFixtures.explicit() else emptyList(),
    )

    private fun analyseSection(axis: String, chainage: Double, offset: Double, height: Double?): AxisAnalysisValue {
        val alignment = AlignmentFixtures.alignment(axis)
        val geometry = AlignmentGeometry(alignment)
        val tip = geometry.pointAt(chainage, offset)!!
        return AxisAnalysis.of(
            geometry,
            alignment.profile?.let { VerticalProfileEval(it) },
            AxisHeightRef.SECTION,
            null,
            tip.easting,
            tip.northing,
            height,
            resolver(axis),
        )
    }

    @Test
    fun `S050 RF-20 la referencia Seccion da la cota de la seccion en el desplazamiento real de la punta`() {
        for (p in SectionFixtures.points().filter { it.axis == "Eje A" && it.code in setOf("BI", "BD") }) {
            val got = projected(analyseSection("Eje A", p.chainage, p.offset, p.elevation!! + 0.12))

            got.heightRef shouldBe AxisHeightRef.SECTION
            val diff = got.height.shouldBeInstanceOf<AxisHeightDiff.Diff>()
            diff.referenceM shouldBe (p.elevation!! plusOrMinus 1e-5)
            diff.dHeightM.shouldNotBeNull() shouldBe (0.12 plusOrMinus 1e-5)
        }
    }

    @Test
    fun `S050 RF-20 entre dos puntos de la seccion la cota se interpola en el desplazamiento de la punta`() {
        // Eje A, PK 0: EJE 100,00 y BD 99,93 (3,5 m): a 1,75 m, 99,965.
        val diff = projected(analyseSection("Eje A", 0.0, 1.75, 99.0)).height.shouldBeInstanceOf<AxisHeightDiff.Diff>()

        diff.referenceM shouldBe (99.965 plusOrMinus 1e-6)
        diff.dHeightM.shouldNotBeNull() shouldBe (-0.965 plusOrMinus 1e-6)
    }

    @Test
    fun `S050 RF-20 mas alla de los puntos exteriores de la seccion se indica`() {
        projected(analyseSection("Eje A", 0.0, 9.0, 99.0)).height shouldBe AxisHeightDiff.OutsideSection
    }

    @Test
    fun `S050 RF-20 un PK sin seccion o entre secciones que no se interpolan se indica`() {
        projected(analyseSection("Eje C", 195.0, 0.0, 5.0)).height shouldBe AxisHeightDiff.NoSection
        projected(analyseSection("Eje C", 125.0, 0.0, 5.0)).height shouldBe AxisHeightDiff.NoSection
    }

    @Test
    fun `S050 RF-20 una seccion absoluta da cota aunque el eje no tenga rasante`() {
        val diff = projected(analyseSection("Eje C", 25.0, 0.0, 101.0)).height.shouldBeInstanceOf<AxisHeightDiff.Diff>()

        diff.referenceM shouldBe (100.65 plusOrMinus 1e-9)
    }

    @Test
    fun `S050 RF-20 sin secciones en el eje la referencia Seccion no da diferencia`() {
        val alignment = AlignmentFixtures.alignment("Eje A")
        val tip = AlignmentGeometry(alignment).pointAt(100.0, 0.0)!!
        val value = AxisAnalysis.of(AlignmentGeometry(alignment), null, AxisHeightRef.SECTION, null, tip.easting, tip.northing, 1.0, null)

        projected(value).height shouldBe AxisHeightDiff.None
    }

    @Test
    fun `S050 RF-20 una seccion relativa sin rasante no da cota y se indica sin referencia`() {
        val alignment = AlignmentFixtures.alignment("Eje A")
        val geometry = AlignmentGeometry(alignment)
        val tip = geometry.pointAt(100.0, 0.0)!!
        val value = AxisAnalysis.of(geometry, null, AxisHeightRef.SECTION, null, tip.easting, tip.northing, 1.0, resolver("Eje A"))

        projected(value).height shouldBe AxisHeightDiff.None
    }
}

package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.Superelevation
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.PI
import org.junit.jupiter.api.Test

/**
 * Un eje de 5.000 tramos con 2.000 secciones explícitas de 11 puntos, una sección tipo y un peralte de 500 filas: lo que se calcula con
 * cada época de un replanteo por código (el punto de diseño con su cota de la sección, su PK y la guía a lo largo y a través) no pasa de
 * 16 ms, ni de media ni en la peor época (S050/RF-18, RNF-T13).
 */
class SectionStakePerformanceTest {

    private fun longAxis(): List<AlignmentElement> {
        val elements = ArrayList<AlignmentElement>()
        var chainage = 0.0
        var east = 440_000.0
        var north = 4_474_000.0
        var azimuth = PI / 2
        var sign = 1.0
        val k = 1.0 / 400

        fun add(element: AlignmentElement) {
            elements += element
            val end = ElementGeometry.stateAt(element, element.length)
            east = end.easting
            north = end.northing
            azimuth = end.azimuth
            chainage += element.length
        }
        repeat(200) {
            add(AlignmentElement.line(chainage, 100.0, east, north, azimuth))
            add(AlignmentElement.clothoid(chainage, 50.0, east, north, azimuth, 0.0, sign * k))
            add(AlignmentElement.arc(chainage, 50.0, east, north, azimuth, sign * k))
            add(AlignmentElement.clothoid(chainage, 50.0, east, north, azimuth, sign * k, 0.0))
            sign = -sign
        }
        while (elements.size < 5_000) add(AlignmentElement.line(chainage, 10.0, east, north, azimuth))
        return elements
    }

    private val geometry = AlignmentGeometry(longAxis())
    private val profile = VerticalProfileEval(
        VerticalProfile(listOf(VerticalVertex(0.0, 100.0), VerticalVertex(geometry.endChainage, 150.0))),
    )

    private val codes = listOf("C1", "C2", "C3", "C4", "C5", "EJE", "D1", "D2", "D3", "D4", "D5")
    private val designs = (0 until 2_000).map { i ->
        CrossSection(
            SectionKind.DESIGN,
            i * 12.0,
            HeightKind.RELATIVE,
            codes.mapIndexed { j, code -> SectionPoint(code, (j - 5) * 1.5, -0.02 * kotlin.math.abs(j - 5) * 1.5) },
        )
    }
    private val template = SectionTemplate(
        24_000.0,
        geometry.endChainage,
        "EJE",
        listOf(TemplateSegment("BI", 3.5, 0.02, true)),
        listOf(TemplateSegment("BD", 3.5, 0.02, true)),
    )
    private val superelevation =
        Superelevation((0 until 500).map { SuperelevationRow(it * 50.0, -0.02 - it % 5 * 0.01, 0.02 + it % 5 * 0.01) })
    private val resolver = SectionResolver(listOf(template), superelevation, designs)

    /** Lo que se calcula con una época: el punto de diseño por código, el PK de la punta y la guía a lo largo y a través. */
    private fun epoch(i: Int) {
        val result = SectionStakeTarget.of(geometry, profile, resolver, 12_000.0 + (i % 1_000) * 0.12, "D3", 0.1)
            .shouldBeInstanceOf<SectionStakeResult.Target>()
        val tipEast = result.target.point.easting + 0.3
        val tipNorth = result.target.point.northing - 0.2
        geometry.locate(tipEast, tipNorth).shouldBeInstanceOf<AxisLocation>()
        AxisGuidance.of(result.target, tipEast, tipNorth)
    }

    @Test
    fun `S050 RNF-T13 el punto de diseno por codigo y la guia de una epoca no pasan de 16 ms en un eje de 5000 tramos`() {
        val epochs = 600
        val times = DoubleArray(epochs)
        // Calentamiento: la primera época carga las clases y compila el código caliente, y eso no es lo que se mide.
        repeat(WARM_UP) { epoch(it) }
        for (i in 0 until epochs) {
            val start = System.nanoTime()
            epoch(i)
            times[i] = (System.nanoTime() - start) / 1e6
        }

        (times.sum() / epochs) shouldBeLessThan 16.0
        times.max() shouldBeLessThan 16.0
        epochs shouldBe times.size
    }

    private companion object {
        const val WARM_UP = 300
    }
}

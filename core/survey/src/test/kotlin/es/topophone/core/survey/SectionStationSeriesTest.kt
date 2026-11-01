package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SuperelevationRow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los pares (PK, código) de un replanteo por código y sus puntos singulares (S050/RF-16, RF-17; plan A4). */
class SectionStationSeriesTest {
    private val codes = listOf("BI", "EJE", "BD")

    private fun items(series: SectionStationSeries) = (0 until series.size).map { series.itemAt(it) }

    private fun series(order: SectionStationOrder, extra: List<Double>? = null) =
        SectionStationSeries(SectionStationOrder.entries.first { it == order }, codes, 0.0, 40.0, 20.0, extra, 100.0)

    @Test
    fun `S050 RF-16 de PK en PK recorre todos los codigos de cada PK`() {
        items(series(SectionStationOrder.BY_CHAINAGE)) shouldBe listOf(
            SectionStationItem(0.0, "BI"), SectionStationItem(0.0, "EJE"), SectionStationItem(0.0, "BD"),
            SectionStationItem(20.0, "BI"), SectionStationItem(20.0, "EJE"), SectionStationItem(20.0, "BD"),
            SectionStationItem(40.0, "BI"), SectionStationItem(40.0, "EJE"), SectionStationItem(40.0, "BD"),
        )
    }

    @Test
    fun `S050 RF-16 de codigo en codigo recorre todos los PK de cada codigo`() {
        items(series(SectionStationOrder.BY_CODE)) shouldBe listOf(
            SectionStationItem(0.0, "BI"), SectionStationItem(20.0, "BI"), SectionStationItem(40.0, "BI"),
            SectionStationItem(0.0, "EJE"), SectionStationItem(20.0, "EJE"), SectionStationItem(40.0, "EJE"),
            SectionStationItem(0.0, "BD"), SectionStationItem(20.0, "BD"), SectionStationItem(40.0, "BD"),
        )
    }

    @Test
    fun `S050 RF-17 los puntos singulares se mezclan por orden y sin repetir`() {
        val s = series(SectionStationOrder.BY_CHAINAGE, extra = listOf(30.0, 20.0004, 45.0))

        (0 until s.size).map { s.itemAt(it).chainageM }.distinct() shouldBe listOf(0.0, 20.0, 30.0, 40.0)
    }

    @Test
    fun `S050 RF-16 siguiente y anterior no pasan de los extremos`() {
        val s = series(SectionStationOrder.BY_CHAINAGE)

        s.next(s.size - 1) shouldBe s.size - 1
        s.previous(0) shouldBe 0
        s.next(0) shouldBe 1
    }

    @Test
    fun `S050 RF-16 sin intervalo solo se replantea el PK inicial con todos los codigos`() {
        val s = SectionStationSeries(SectionStationOrder.BY_CHAINAGE, codes, 12.0, null, null, null, 100.0)

        items(s) shouldBe codes.map { SectionStationItem(12.0, it) }
    }

    @Test
    fun `S050 RF-16 sin codigos la serie esta vacia`() {
        SectionStationSeries(SectionStationOrder.BY_CHAINAGE, emptyList(), 0.0, 40.0, 20.0, null, 100.0).size shouldBe 0
    }

    @Test
    fun `S050 RF-17 los PK donde cambia la seccion y los del peralte son singulares`() {
        val template = SectionFixtures.template("Eje A")!!
        val design = listOf(
            CrossSection(SectionKind.DESIGN, 10.0, HeightKind.ABSOLUTE, listOf(SectionPoint("EJE", 0.0, 1.0))),
            CrossSection(SectionKind.DESIGN, 30.0, HeightKind.ABSOLUTE, listOf(SectionPoint("EJE", 0.0, 1.0))),
            CrossSection(SectionKind.GROUND, 20.0, HeightKind.ABSOLUTE, listOf(SectionPoint("T1", 0.0, 1.0))),
        )
        val rows = listOf(SuperelevationRow(120.0, 0.02, 0.02), SuperelevationRow(180.0, -0.06, 0.06))

        SectionKeyStations.of(listOf(template), design, rows) shouldBe listOf(0.0, 10.0, 30.0, 120.0, 180.0, 420.0)
    }
}

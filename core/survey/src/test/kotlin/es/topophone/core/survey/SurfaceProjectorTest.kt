package es.topophone.core.survey

import es.topophone.core.model.cad.CadIgnoredReason
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** De las coordenadas de un LandXML a las del proyecto, frente a pyproj (S042/RF-6, RF-7, RF-8; constitución nº 2). */
class SurfaceProjectorTest {

    private fun converted(projection: SurfaceProjection) = projection as SurfaceProjection.Converted

    @Test
    fun `S042 RF-6 los vertices en UTM 29 salen en UTM 30 como en pyproj a menos de 1 mm`() {
        val rows = SurveyFixtures.rows("conversion.csv", "superficies")
        val projector = SurfaceProjector(ProjectCrs.ETRS89_UTM30, CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))

        rows.size shouldBe 42
        val out = converted(
            projector.convert(
                DoubleArray(rows.size) { rows[it][1].toDouble() },
                DoubleArray(rows.size) { rows[it][2].toDouble() },
                DoubleArray(rows.size) { 600.0 + it },
            ),
        )
        rows.forEachIndexed { i, row ->
            out.easting[i] shouldBe (row[3].toDouble() plusOrMinus 1e-3)
            out.northing[i] shouldBe (row[4].toDouble() plusOrMinus 1e-3)
            out.height[i] shouldBe (600.0 + i plusOrMinus 1e-12)
        }
    }

    @Test
    fun `S042 RF-6 el terreno de en-utm29 convertido es el terreno de dos-superficies a menos de 1 mm`() {
        val utm29 = SurfaceFixtures.surface("en-utm29.xml", "terreno")
        val utm30 = SurfaceFixtures.terrain()

        val out =
            converted(
                SurfaceProjector(
                    ProjectCrs.ETRS89_UTM30,
                    CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29),
                ).convert(utm29.easting, utm29.northing, utm29.height),
            )

        for (i in out.easting.indices) {
            out.easting[i] shouldBe (utm30.easting[i] plusOrMinus 1e-3)
            out.northing[i] shouldBe (utm30.northing[i] plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-7 las coordenadas y las cotas en pies internacionales salen en metros`() {
        val feet = SurfaceFixtures.surface("en-pies.xml", "terreno")
        val metres = SurfaceFixtures.terrain()

        val out =
            converted(
                SurfaceProjector(
                    ProjectCrs.ETRS89_UTM30,
                    unit = LinearUnit.INTERNATIONAL_FOOT,
                ).convert(feet.easting, feet.northing, feet.height),
            )

        for (i in out.easting.indices) {
            out.easting[i] shouldBe (metres.easting[i] plusOrMinus 1e-3)
            out.northing[i] shouldBe (metres.northing[i] plusOrMinus 1e-3)
            out.height[i] shouldBe (metres.height[i] plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S042 RF-7 el pie topografico de EE UU usa 1200 entre 3937`() {
        val out = converted(
            SurfaceProjector(ProjectCrs.ETRS89_UTM30, unit = LinearUnit.US_SURVEY_FOOT)
                .convert(doubleArrayOf(1_445_000.0), doubleArrayOf(14_660_000.0), doubleArrayOf(1_970.0)),
        )

        out.height[0] shouldBe (1_970.0 * 1200.0 / 3937.0 plusOrMinus 1e-9)
        out.easting[0] shouldBe (1_445_000.0 * 1200.0 / 3937.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S042 RF-6 en el sistema del proyecto se guarda tal cual y la cota no cambia`() {
        val s = SurfaceFixtures.terrain()

        val out = converted(SurfaceProjector(ProjectCrs.ETRS89_UTM30).convert(s.easting, s.northing, s.height))

        out.easting.toList() shouldBe s.easting.toList()
        out.northing.toList() shouldBe s.northing.toList()
        out.height.toList() shouldBe s.height.toList()
    }

    @Test
    fun `S042 RF-6 un vertice fuera del ambito del sistema rechaza la superficie entera`() {
        val out = SurfaceProjector(ProjectCrs.ETRS89_UTM30).convert(
            doubleArrayOf(440_000.0, 5_000_000.0),
            doubleArrayOf(4_474_000.0, 10.0),
            doubleArrayOf(1.0, 2.0),
        )

        out shouldBe SurfaceProjection.Rejected(CadIgnoredReason.OUTSIDE_CRS_AREA)
    }

    @Test
    fun `S042 RF-8 la extension aproximada es el recuadro de las cuatro esquinas convertidas`() {
        val s = SurfaceFixtures.terrain()
        val projector = SurfaceProjector(ProjectCrs.ETRS89_UTM30, CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29))
        val utm29 = SurfaceFixtures.surface("en-utm29.xml", "terreno")

        val extent = projector.cornersExtent(utm29.easting.min(), utm29.easting.max(), utm29.northing.min(), utm29.northing.max())!!

        // Con el giro de la cuadricula entre husos el recuadro de las esquinas contiene la extension exacta.
        (extent.minEasting <= s.easting.min() + 1e-3) shouldBe true
        (extent.maxEasting >= s.easting.max() - 1e-3) shouldBe true
        (extent.minNorthing <= s.northing.min() + 1e-3) shouldBe true
        (extent.maxNorthing >= s.northing.max() - 1e-3) shouldBe true
        (extent.maxEasting - extent.minEasting < 400.0) shouldBe true
    }

    @Test
    fun `S042 RF-8 sin esquinas dentro del ambito no hay extension`() {
        SurfaceProjector(ProjectCrs.ETRS89_UTM30).cornersExtent(5_000_000.0, 5_000_100.0, 10.0, 20.0).shouldBeNull()
    }

    @Test
    fun `S042 RF-6 una superficie sin vertices se convierte en nada`() {
        converted(SurfaceProjector(ProjectCrs.ETRS89_UTM30).convert(DoubleArray(0), DoubleArray(0), DoubleArray(0))).easting.size shouldBe 0
    }
}

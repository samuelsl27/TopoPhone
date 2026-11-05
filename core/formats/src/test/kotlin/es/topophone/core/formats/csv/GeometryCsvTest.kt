package es.topophone.core.formats.csv

import es.topophone.core.model.geometry.DistanceMode
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** El CSV de la tabla de segmentos de la información geométrica (S026/RF-8, RNF-T9). */
class GeometryCsvTest {

    private val segments = listOf(
        GeometryCsvSegment(1, "P1", "P2", 0.0, 100.0, 100.5, Math.PI / 2, 10.0, 10.0),
        GeometryCsvSegment(2, "P2", "P3", 100.0, 50.12346, null, 3 * Math.PI / 2, null, null),
    )

    private fun csv(
        totals: GeometryCsvTotals,
        mode: DistanceMode = DistanceMode.GRID,
        linear: LinearUnit = LinearUnit.METRE,
        angular: AngularUnit = AngularUnit.DECIMAL_DEGREES,
    ): List<String> {
        val out = StringBuilder()
        GeometryCsv.write(segments, totals, mode, linear, angular, out)
        return out.toString().split("\r\n")
    }

    @Test
    fun `S026 RF-8 una fila por segmento y una de totales con la distancia y las unidades en la cabecera`() {
        val lines = csv(GeometryCsvTotals(150.12346, null, null, null))
        lines[0] shouldBe "Segment,From,To,Chainage (m grid),Length 2D (m grid),Length 3D (m grid),Azimuth (deg),Delta height (m),Slope (%)"
        lines[1] shouldBe "1,P1,P2,0.0000,100.0000,100.5000,90.0000000,10.0000,10.0000"
        lines[2] shouldBe "2,P2,P3,100.0000,50.1235,,270.0000000,,"
        lines[3] shouldBe "Total,,,,150.1235,,,,"
        lines[4] shouldBe ""
    }

    @Test
    fun `S026 RF-8 un poligono suma el perimetro y el area con su unidad`() {
        val lines = csv(GeometryCsvTotals(150.0, 160.0, 150.0, 1234.5))
        lines[3] shouldBe "Total,,,,150.0000,160.0000,,,"
        lines[4] shouldBe "Perimeter,,,,150.0000,,,,"
        lines[5] shouldBe "Area (m2),,,,1234.5000,,,,"
    }

    @Test
    fun `S026 RF-8 las unidades del proyecto y el modo de terreno salen en la cabecera y en los numeros`() {
        val lines = csv(GeometryCsvTotals(100.0, null, null, 100.0), DistanceMode.GROUND, LinearUnit.INTERNATIONAL_FOOT, AngularUnit.GON)
        lines[0] shouldBe
            "Segment,From,To,Chainage (ft ground),Length 2D (ft ground),Length 3D (ft ground),Azimuth (gon),Delta height (ft),Slope (%)"
        lines[1] shouldBe "1,P1,P2,0.0000,328.0840,329.7244,100.0000000,32.8084,10.0000"
        lines[4] shouldBe "Area (ft2),,,,1076.3910,,,,"
    }

    @Test
    fun `S026 RF-8 en grados sexagesimales el azimut va como texto`() {
        csv(GeometryCsvTotals(1.0, null, null, null), angular = AngularUnit.DMS)[1].split(",").let { cells ->
            cells[6] shouldBe "90° 00′ 00″"
        }
    }

    @Test
    fun `S026 RF-8 el punto decimal no depende de la configuracion regional`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            csv(GeometryCsvTotals(1.5, null, null, null))[1] shouldBe "1,P1,P2,0.0000,100.0000,100.5000,90.0000000,10.0000,10.0000"
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `S026 RF-8 los nombres con comas van entre comillas`() {
        val out = StringBuilder()
        GeometryCsv.write(
            listOf(GeometryCsvSegment(1, "A,1", "B\"2", 0.0, 1.0, null, 0.0, null, null)),
            GeometryCsvTotals(1.0, null, null, null),
            DistanceMode.GRID,
            LinearUnit.METRE,
            AngularUnit.DECIMAL_DEGREES,
            out,
        )
        out.toString().split("\r\n")[1] shouldBe "1,\"A,1\",\"B\"\"2\",0.0000,1.0000,,0.0000000,,"
    }
}

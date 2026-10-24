package es.topophone.core.formats.csv

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.StakeoutReport
import es.topophone.core.model.point.StakeoutReportRow
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El CSV del informe de replanteo (S019/RF-21, RF-22, RF-23), con las reglas de formato de S012. */
class StakeoutReportCsvTest {

    private fun project(crs: ProjectCrs = ProjectCrs.ETRS89_UTM30, unit: LinearUnit = LinearUnit.METRE) = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = crs,
        linearUnit = unit,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val utmHeader =
        "Design name,Stakeout number,Staked name,Description,Design easting,Design northing,Design elevation," +
            "Measured easting,Measured northing,Measured elevation,Delta northing,Delta easting,Horizontal distance," +
            "Delta elevation,Horizontal tolerance,Vertical tolerance,Within tolerance,Last,Solution,Measured from"
    private val geographicHeader =
        "Design name,Stakeout number,Staked name,Description,Design latitude,Design longitude,Design elevation," +
            "Measured latitude,Measured longitude,Measured elevation,Delta northing,Delta easting,Horizontal distance," +
            "Delta elevation,Horizontal tolerance,Vertical tolerance,Within tolerance,Last,Solution,Measured from"

    /** Un replanteo con números que redondean sin dudas a 4 decimales. */
    private fun row(
        description: String = "Esquina, norte",
        elevation: Double? = 700.0,
        dHeight: Double? = 0.02,
        tolerances: Pair<Double, Double>? = 0.025 to 0.03,
        within: Boolean? = true,
        last: Boolean = true,
        solution: String = "FIX",
        number: Int = 2,
    ): StakeoutReportRow {
        val design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.42), 440_000.1234, 4_470_000.5, elevation)
        val measured = PointCoordinates(GeodeticPosition(40.41681, -3.70379, 699.44), 440_000.14, 4_470_000.49, 700.02)
        val ref = StakeoutRef(
            designId = "d1",
            designName = "P1",
            design = design,
            dEastM = 0.01656,
            dNorthM = -0.01,
            dHorizontalM = 0.019337,
            dHeightM = dHeight,
            designElevationM = elevation,
            toleranceHM = tolerances?.first,
            toleranceVM = tolerances?.second,
            withinTolerance = within,
        )
        val measurement = Measurement(
            antenna = measured.ground,
            poleHeightM = 1.8,
            quality = PointQuality(solution, 24, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = measured,
        )
        val point = Point.measured("id-1", "p1", "P1_stkd$number", description, measurement, 1_792_346_639_419, ref)
        return StakeoutReportRow(point, ref, number, last)
    }

    private fun csv(
        rows: List<StakeoutReportRow>,
        project: Project = project(),
        separator: ColumnSeparator = ColumnSeparator.COMMA,
        decimal: DecimalSeparator = DecimalSeparator.POINT,
    ): String = StringBuilder().also { StakeoutReportCsv.write(rows, project, separator, decimal, it) }.toString()

    private fun lines(text: String): List<String> = text.removeSuffix("\r\n").split("\r\n")

    @Test
    fun `S019 RF-21 la primera fila lleva los nombres de las columnas y el resto una fila por replanteo`() {
        val text = csv(listOf(row(), row(number = 3, last = false)))

        val lines = lines(text)
        lines.size shouldBe 3
        lines[0] shouldBe utmHeader
        text.endsWith("\r\n") shouldBe true
    }

    @Test
    fun `S019 RF-21 cada fila lleva las columnas por el orden de la spec con 4 decimales`() {
        lines(csv(listOf(row())))[1] shouldBe
            "P1,2,P1_stkd2,\"Esquina, norte\",440000.1234,4470000.5000,700.0000," +
            "440000.1400,4470000.4900,700.0200,-0.0100,0.0166,0.0193,0.0200,0.0250,0.0300,true,true,FIX,2026-10-18T18:03:34Z"
    }

    @Test
    fun `S019 RF-21 el numero de replanteo y si es el ultimo salen en su columna`() {
        val first = lines(csv(listOf(row(description = "Eje", number = 1, last = false))))[1].split(",")
        val third = lines(csv(listOf(row(description = "Eje", number = 3, last = true))))[1].split(",")

        first[1] shouldBe "1"
        first[17] shouldBe "false"
        third[1] shouldBe "3"
        third[17] shouldBe "true"
    }

    @Test
    fun `S019 RF-23 sin cota la celda de la cota de diseno y la de la diferencia de cota quedan vacias`() {
        val line = lines(csv(listOf(row(description = "Eje", elevation = null, dHeight = null))))[1]

        line shouldBe
            "P1,2,P1_stkd2,Eje,440000.1234,4470000.5000,,440000.1400,4470000.4900,700.0200,-0.0100,0.0166,0.0193,,0.0250,0.0300,true,true,FIX,2026-10-18T18:03:34Z"
    }

    @Test
    fun `S019 RF-26 un replanteo sin evaluar deja vacias las tolerancias y el dentro de la tolerancia`() {
        val line = lines(csv(listOf(row(description = "Antes", tolerances = null, within = null))))[1]

        line shouldBe
            "P1,2,P1_stkd2,Antes,440000.1234,4470000.5000,700.0000,440000.1400,4470000.4900,700.0200,-0.0100,0.0166,0.0193,0.0200,,,,true,FIX,2026-10-18T18:03:34Z"
    }

    @Test
    fun `S019 RF-23 dentro de la tolerancia false se escribe como false`() {
        val cells = lines(csv(listOf(row(description = "x", within = false))))[1].split(",")

        cells[16] shouldBe "false"
    }

    @Test
    fun `S019 RF-22 en un SC geografico se escriben la latitud y la longitud en lugar del Este y el Norte`() {
        val text = csv(listOf(row(description = "Eje")), project(ProjectCrs.ETRS89_GEOGRAPHIC))

        val lines = lines(text)
        lines[0] shouldBe geographicHeader
        lines[1] shouldBe
            "P1,2,P1_stkd2,Eje,40.416800000,-3.703800000,700.0000," +
            "40.416810000,-3.703790000,700.0200,-0.0100,0.0166,0.0193,0.0200,0.0250,0.0300,true,true,FIX,2026-10-18T18:03:34Z"
    }

    @Test
    fun `S019 RF-23 las longitudes salen en la unidad del proyecto`() {
        val feet = lines(csv(listOf(row(description = "Eje")), project(unit = LinearUnit.INTERNATIONAL_FOOT)))[1].split(",")

        feet[6] shouldBe "2296.5879" // 700 m en pies internacionales
        feet[12] shouldBe "0.0634" // 0,019337 m
        feet[14] shouldBe "0.0820" // 0,025 m
    }

    @Test
    fun `S019 RF-23 con punto y coma y coma decimal los numeros llevan coma y el fichero no cambia de columnas`() {
        val line = lines(csv(listOf(row(description = "Eje")), separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA))[1]

        line shouldBe
            "P1;2;P1_stkd2;Eje;440000,1234;4470000,5000;700,0000;440000,1400;4470000,4900;700,0200;-0,0100;0,0166;0,0193;0,0200;0,0250;0,0300;true;true;FIX;2026-10-18T18:03:34Z"
    }

    @Test
    fun `S019 RF-23 con tabulador de separador`() {
        val line = lines(csv(listOf(row(description = "Eje")), separator = ColumnSeparator.TAB))[1]

        line.split("\t").size shouldBe 20
    }

    @Test
    fun `S019 RF-23 un texto con el separador, comillas o saltos de linea va entre comillas dobles`() {
        val line = csv(listOf(row(description = "a,\"b\"\nc")))

        line.contains("\"a,\"\"b\"\"\nc\"") shouldBe true
    }

    @Test
    fun `S019 RF-23 con la coma de separador y de decimal no se escribe`() {
        shouldThrow<IllegalArgumentException> {
            csv(listOf(row()), separator = ColumnSeparator.COMMA, decimal = DecimalSeparator.COMMA)
        }
    }

    @Test
    fun `S019 RF-23 sin filas solo se escribe la cabecera`() {
        lines(csv(emptyList())) shouldBe listOf(utmHeader)
    }

    @Test
    fun `S019 RF-21 la solucion de la medida y el inicio de la medida en UTC salen en sus columnas`() {
        val cells = lines(csv(listOf(row(description = "Eje", solution = "FLOAT"))))[1].split(",")

        cells[18] shouldBe "FLOAT"
        cells[19] shouldBe "2026-10-18T18:03:34Z"
    }

    @Test
    fun `S019 RF-21 con los fixtures de pyproj las diferencias y la cota escritas coinciden a menos de 0,1 mm`() {
        val rows = ReplanteoFixtures.informe().filter { it.getValue("proyecto") == "UTM30" }
        val report = StakeoutReport.of(ReplanteoFixtures.points("UTM30"))

        val written = lines(csv(report.rows)).drop(1).map { it.split(",") }

        written.size shouldBe rows.size
        written.zip(rows).forEach { (cells, expected) ->
            cells[2] shouldBe expected.getValue("nombre")
            cells[10].toDouble() shouldBe (expected.getValue("d_norte").toDouble() plusOrMinus 5e-5)
            cells[11].toDouble() shouldBe (expected.getValue("d_este").toDouble() plusOrMinus 5e-5)
            cells[12].toDouble() shouldBe (expected.getValue("d_horizontal").toDouble() plusOrMinus 5e-5)
            if (expected.getValue("d_cota").isEmpty()) {
                cells[13] shouldBe ""
            } else {
                cells[13].toDouble() shouldBe
                    (expected.getValue("d_cota").toDouble() plusOrMinus 5e-5)
            }
            cells[16] shouldBe when (expected.getValue("dentro")) {
                "1" -> "true"
                "0" -> "false"
                else -> ""
            }
            cells[17] shouldBe if (expected.getValue("ultimo") == "1") "true" else "false"
        }
    }
}

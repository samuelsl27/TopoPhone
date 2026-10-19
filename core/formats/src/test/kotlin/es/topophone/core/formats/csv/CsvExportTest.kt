package es.topophone.core.formats.csv

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** CSV de los puntos de un proyecto (S012/RF-5 a RF-16). */
class CsvExportTest {

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val measured = Point.measured(
        id = "id-4",
        projectId = "p1",
        name = "4",
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416815094, -3.703533016, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FLOAT", 24, 0.6, 1.0, 0.1382931668593933, 0.21, MeasurementMode.INSTANT, 1, 0, fromReplay = true),
            rawNmea = "\$GNGGA,1*00",
            outOfTolerance = setOf(PrecisionFailure.NOT_FIX),
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = PointCoordinates(
                GeodeticPosition(40.416815094, -3.703533016, 699.423),
                440_313.1221195372,
                4_474_258.876880059,
                648.3113666754832,
            ),
        ),
        createdAtMs = 1_792_346_639_419,
    )

    private val manual = Point(
        id = "id-E1",
        projectId = "p1",
        name = "E1",
        description = "Pozo, \"nuevo\"",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4168, -3.7036, null), 440_300.0, 4_474_250.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = true,
        copyOf = null,
        createdAtMs = 1_792_346_700_000,
        deletedAtMs = null,
    )

    private val allHeader =
        "Name,Description,Origin,Easting,Northing,Elevation,Latitude,Longitude,Ellipsoidal height,CRS,Height system,Units," +
            "Pole height,Solution,Satellites,HDOP,Correction age,Horizontal accuracy,Vertical accuracy,Mode," +
            "Epochs used,Epochs discarded,Replay,Out of tolerance,Measured from,Measured to,Edited,Copy of"

    private fun csv(points: List<Point>, options: CsvOptions, project: Project = this.project): String =
        StringBuilder().also { CsvExport.write(points.asSequence(), project, options, it) }.toString()

    private fun lines(text: String): List<String> = text.removeSuffix("\r\n").split("\r\n")

    @Test
    fun `S012 RF-5, RF-6 y RF-9 todas las columnas, con su cabecera, en el orden de la spec`() {
        val text = csv(listOf(measured), CsvOptions())

        lines(text) shouldBe listOf(
            allHeader,
            "4,,Measured,440313.1221,4474258.8769,648.3114,40.416815094,-3.703533016,699.4230,EPSG:25830,EGM08-REDNAP,m," +
                "1.8000,FLOAT,24,0.6,1.0,0.1383,0.2100,Instant,1,0,true,NOT_FIX,2026-10-18T18:03:34Z,2026-10-18T18:03:59Z,false,",
        )
    }

    @Test
    fun `S012 RF-7 y RF-9 PENZD y PNEZD con cabecera`() {
        lines(csv(listOf(measured), CsvOptions(format = ExportFormat.PENZD))) shouldBe listOf(
            "Name,Easting,Northing,Elevation,Description",
            "4,440313.1221,4474258.8769,648.3114,",
        )
        lines(csv(listOf(measured), CsvOptions(format = ExportFormat.PNEZD))) shouldBe listOf(
            "Name,Northing,Easting,Elevation,Description",
            "4,4474258.8769,440313.1221,648.3114,",
        )
    }

    @Test
    fun `S012 RF-8 el personalizado escribe las columnas marcadas en el orden de todas las columnas`() {
        val options =
            CsvOptions(
                format = ExportFormat.CUSTOM,
                customColumns = setOf(ExportColumn.ELLIPSOIDAL_HEIGHT, ExportColumn.NAME, ExportColumn.LATITUDE),
            )

        lines(csv(listOf(measured), options)) shouldBe listOf("Name,Latitude,Ellipsoidal height", "4,40.416815094,699.4230")
    }

    @Test
    fun `S012 RF-10 en un SC geografico no hay PENZD ni PNEZD`() {
        CsvExport.formatsFor(ProjectCrs.ETRS89_UTM30) shouldBe ExportFormat.entries
        CsvExport.formatsFor(ProjectCrs.ETRS89_GEOGRAPHIC) shouldBe listOf(ExportFormat.ALL, ExportFormat.CUSTOM)
        CsvExport.formatsFor(ProjectCrs.REGCAN95_GEOGRAPHIC) shouldBe listOf(ExportFormat.ALL, ExportFormat.CUSTOM)
    }

    @Test
    fun `S012 RF-11 las longitudes van en la unidad del proyecto con 4 decimales`() {
        val feet =
            lines(csv(listOf(measured), CsvOptions(format = ExportFormat.PENZD), project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT)))
        feet[1] shouldBe "4,1444596.8573,14679327.0239,2127.0058,"
        val usFeet =
            lines(csv(listOf(measured), CsvOptions(format = ExportFormat.PENZD), project.copy(linearUnit = LinearUnit.US_SURVEY_FOOT)))
        usFeet[1] shouldBe "4,1444593.9682,14679297.6652,2127.0015,"
        val units = lines(csv(listOf(measured), CsvOptions(), project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT)))[1].split(",")
        units[11] shouldBe "ft"
        units[12] shouldBe "5.9055"
    }

    @Test
    fun `S012 RF-12 lo que el punto no tiene queda vacio y la papelera ni se ve aqui`() {
        val row = lines(csv(listOf(manual), CsvOptions(separator = ColumnSeparator.SEMICOLON)))[1]

        // Los 14 campos de la medida, vacíos.
        row shouldBe "E1;\"Pozo, \"\"nuevo\"\"\";Manual;440300.0000;4474250.0000;;40.416800000;-3.703600000;;EPSG:25830;EGM08-REDNAP;m;" +
            ";;;;;;;;;;;;;;false;"
        row.split(";").size shouldBe 28
    }

    @Test
    fun `S012 RF-13 separador punto y coma con coma decimal, y tabulador`() {
        lines(
            csv(
                listOf(measured),
                CsvOptions(format = ExportFormat.PENZD, separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA),
            ),
        )[1] shouldBe
            "4;440313,1221;4474258,8769;648,3114;"
        lines(csv(listOf(measured), CsvOptions(format = ExportFormat.PENZD, separator = ColumnSeparator.TAB)))[1] shouldBe
            "4\t440313.1221\t4474258.8769\t648.3114\t"
    }

    @Test
    fun `S012 RF-14 coma de separador y de decimal no vale`() {
        val options = CsvOptions(separator = ColumnSeparator.COMMA, decimal = DecimalSeparator.COMMA)

        options.valid shouldBe false
        shouldThrow<IllegalArgumentException> { csv(listOf(measured), options) }
    }

    @Test
    fun `S012 RF-15 los textos con separador, comillas o saltos de linea van entre comillas`() {
        val tricky = manual.copy(name = "P;1", description = "línea 1\nlínea \"2\"\ttab")

        lines(csv(listOf(tricky), CsvOptions(format = ExportFormat.PENZD)))[1] shouldBe
            "P;1,440300.0000,4474250.0000,,\"línea 1\nlínea \"\"2\"\"\ttab\""
        csv(listOf(tricky), CsvOptions(format = ExportFormat.PENZD, separator = ColumnSeparator.SEMICOLON)).split("\r\n")[1] shouldBe
            "\"P;1\";440300.0000;4474250.0000;;\"línea 1\nlínea \"\"2\"\"\ttab\""
        csv(listOf(manual), CsvOptions(format = ExportFormat.PENZD, separator = ColumnSeparator.TAB)).split("\r\n")[1] shouldBe
            "E1\t440300.0000\t4474250.0000\t\t\"Pozo, \"\"nuevo\"\"\""
    }

    @Test
    fun `S012 RF-16 cada linea acaba en CRLF y las filas van en el orden dado`() {
        val text = csv(listOf(manual, measured), CsvOptions(format = ExportFormat.PENZD))

        text.endsWith("\r\n") shouldBe true
        text.count { it == '\n' } shouldBe 3
        lines(text).map { it.substringBefore(',') } shouldBe listOf("Name", "E1", "4")
    }

    @Test
    fun `S012 RF-11 la latitud y la longitud llevan signo y 9 decimales, y el cero no lleva signo`() {
        val south = manual.copy(
            description = "",
            coordinates = PointCoordinates(GeodeticPosition(-0.0000000001, -3.7, 0.0), -0.00001, 0.0, -0.00004),
        )

        lines(
            csv(
                listOf(south),
                CsvOptions(
                    format = ExportFormat.CUSTOM,
                    customColumns = setOf(
                        ExportColumn.EASTING,
                        ExportColumn.ELEVATION,
                        ExportColumn.LATITUDE,
                        ExportColumn.LONGITUDE,
                        ExportColumn.ELLIPSOIDAL_HEIGHT,
                    ),
                ),
            ),
        )[1] shouldBe "0.0000,0.0000,0.000000000,-3.700000000,0.0000"
    }

    @Test
    fun `S012 RF-6 la altura elipsoidal del proyecto se nombra asi y la copia dice de que punto es`() {
        val copy = manual.copy(copyOf = "P12", description = "")
        val row = lines(csv(listOf(copy), CsvOptions(), project.copy(heightSystem = HeightSystem.ELLIPSOIDAL)))[1].split(",")

        row[10] shouldBe "Ellipsoidal"
        row.last() shouldBe "P12"
    }
}

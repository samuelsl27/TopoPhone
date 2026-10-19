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
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Ficheros dorados del CSV exportado (S012/RF-11, RF-12, RF-15, RF-16; constitución nº 2). `CsvExport` debe escribir
 * exactamente `fixtures/formatos/csv/`, que `tools/formatos/test_csv_exportado.py` lee con el módulo `csv` de Python
 * y compara con `fuente.json`. Con `TOPOPHONE_REGENERAR_DORADOS=1` se vuelven a escribir.
 */
class CsvExportGoldenTest {

    private val folder = File(
        generateSequence(File("").absoluteFile) { it.parentFile }.first {
            File(it, "settings.gradle.kts").exists() &&
                File(it, "fixtures").isDirectory
        },
        "fixtures/formatos/csv",
    )

    private val project = Project(
        id = "p1",
        name = "Dorados",
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

    private fun measured(
        name: String,
        description: String,
        coordinates: PointCoordinates,
        quality: PointQuality,
        failures: Set<PrecisionFailure>,
    ) = Point.measured(
        id = "id-$name",
        projectId = "p1",
        name = name,
        description = description,
        measurement = Measurement(
            antenna = coordinates.ground.copy(ellipsoidalHeightM = coordinates.ground.ellipsoidalHeightM?.plus(1.934)),
            poleHeightM = 1.8,
            quality = quality,
            rawNmea = "",
            outOfTolerance = failures,
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = coordinates,
        ),
        createdAtMs = 1_792_346_639_419,
    )

    private val points = listOf(
        measured(
            "P1",
            "base",
            PointCoordinates(
                GeodeticPosition(40.41668, -3.7037100000000005, 699.4230000000001),
                440_297.98753542645,
                4_474_244.001132507,
                648.3113331296773,
            ),
            PointQuality("FIX", 24, 0.6, 1.0, 0.009219544457292887, 0.014, MeasurementMode.AVERAGED, 20, 6, fromReplay = false),
            emptySet(),
        ),
        measured(
            "P2;b",
            "Pozo, \"nuevo\"",
            PointCoordinates(
                GeodeticPosition(40.416815094, -3.703533016, 699.4230000000001),
                440_313.1221195372,
                4_474_258.876880059,
                648.3113666754832,
            ),
            PointQuality("FLOAT", 18, 1.25, 12.0, 0.1382931668593933, 0.21, MeasurementMode.INSTANT, 1, 0, fromReplay = true),
            setOf(PrecisionFailure.VERTICAL, PrecisionFailure.NOT_FIX),
        ).copy(edited = true),
        Point(
            id = "id-E1",
            projectId = "p1",
            name = "E1",
            description = "línea 1\nlínea 2\ttab;fin",
            origin = PointOrigin.MANUAL,
            coordinates = PointCoordinates(GeodeticPosition(39.5696, 2.6502, null), 997_102.5, 4_398_611.25, null),
            measurement = null,
            typed = null,
            edited = false,
            hidden = true,
            copyOf = "P1",
            createdAtMs = 1_792_346_700_000,
            deletedAtMs = null,
        ),
    )

    private val files = mapOf(
        "todas_coma_punto.csv" to CsvOptions(),
        "todas_puntoycoma_coma.csv" to CsvOptions(separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA),
        "todas_tabulador_punto.csv" to CsvOptions(separator = ColumnSeparator.TAB),
        "penzd_puntoycoma_coma.csv" to
            CsvOptions(format = ExportFormat.PENZD, separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA),
        "pnezd_coma_punto.csv" to CsvOptions(format = ExportFormat.PNEZD),
    )

    private fun written(options: CsvOptions): ByteArray =
        StringBuilder().also { CsvExport.write(points.asSequence(), project, options, it) }.toString().toByteArray(Charsets.UTF_8)

    /** Los valores sin redondear, para que Python compruebe el redondeo (constitución nº 2). */
    private fun source(): String = points.joinToString(",\n", prefix = "[\n", postfix = "\n]\n") { point ->
        val c = point.coordinates
        fun n(value: Double?) = value?.toString() ?: "null"
        val text = point.description.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t")
        """  {"name": "${point.name}", "description": "$text", "easting": ${n(c.easting)}, "northing": ${n(c.northing)}, """ +
            """"height": ${n(c.height)}, "latitude": ${n(c.ground.latitudeDeg)}, "longitude": ${n(c.ground.longitudeDeg)}, """ +
            """"ellipsoidal_height": ${n(c.ground.ellipsoidalHeightM)}, "pole_height": ${n(point.measurement?.poleHeightM)}, """ +
            """"horizontal_accuracy": ${n(
                point.measurement?.quality?.horizontalM,
            )}, "vertical_accuracy": ${n(point.measurement?.quality?.verticalM)}}"""
    }

    @Test
    fun `S012 RF-11, RF-12, RF-15 y RF-16 CsvExport escribe exactamente los ficheros dorados`() {
        if (System.getenv("TOPOPHONE_REGENERAR_DORADOS") == "1") {
            folder.mkdirs()
            files.forEach { (name, options) -> File(folder, name).writeBytes(written(options)) }
            File(folder, "fuente.json").writeText(source(), Charsets.UTF_8)
        }

        val different = files.filter { (name, options) -> !File(folder, name).readBytes().contentEquals(written(options)) }.keys
        different.shouldBeEmpty()
        File(folder, "fuente.json").readText(Charsets.UTF_8) shouldBe source()
    }
}

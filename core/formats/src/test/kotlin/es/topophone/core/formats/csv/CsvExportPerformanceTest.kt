package es.topophone.core.formats.csv

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test

/** Con 10 000 puntos, el fichero se escribe en menos de 3 s (S012, RNF de la spec). La JVM sirve de cota; el móvil es de la demo. */
class CsvExportPerformanceTest {

    private val project = Project(
        id = "p1", name = "Parcelaria", author = "", description = "", crs = ProjectCrs.ETRS89_UTM30, linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS, correctionsDatum = CorrectionsDatum.ETRS89, folderId = null, createdAtMs = 0, modifiedAtMs = 0,
        openedAtMs = null, deletedAtMs = null,
    )

    private val points = List(POINTS) { i ->
        val lat = 40.4 + (i % 100) * 1e-4
        val lon = -3.7 + (i / 100) * 1e-4
        Point.measured(
            id = "id-$i",
            projectId = "p1",
            name = "P$i",
            description = "Bordillo, \"tramo $i\"",
            measurement = Measurement(
                antenna = GeodeticPosition(lat, lon, 700.0),
                poleHeightM = 1.8,
                quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.AVERAGED, 30, 2, false),
                rawNmea = "",
                outOfTolerance = setOf(PrecisionFailure.HORIZONTAL),
                measuredFromMs = 1_792_346_614_419 + i * 1_000L,
                measuredToMs = 1_792_346_644_419 + i * 1_000L,
                coordinates = PointCoordinates(
                    GeodeticPosition(lat, lon, 698.066),
                    440_000.123456 + i,
                    4_474_000.654321 + i,
                    650.0 + i * 1e-3,
                ),
            ),
            createdAtMs = i.toLong(),
        )
    }

    private fun write(options: CsvOptions): ByteArray {
        val bytes = ByteArrayOutputStream()
        BufferedWriter(OutputStreamWriter(bytes, Charsets.UTF_8)).use { CsvExport.write(points.asSequence(), project, options, it) }
        return bytes.toByteArray()
    }

    @Test
    fun `S012 RNF con 10 000 puntos y todas las columnas el fichero se escribe en menos de 3 s`() {
        val options = CsvOptions(separator = ColumnSeparator.SEMICOLON, decimal = DecimalSeparator.COMMA)

        val (bytes, elapsed) = measureTimedValue { write(options) }

        bytes.toString(Charsets.UTF_8).split("\r\n").size shouldBe POINTS + 2 // cabecera, puntos y la línea vacía del CRLF final
        elapsed.inWholeMilliseconds shouldBeLessThan 3_000
    }

    private companion object {
        const val POINTS = 10_000
    }
}

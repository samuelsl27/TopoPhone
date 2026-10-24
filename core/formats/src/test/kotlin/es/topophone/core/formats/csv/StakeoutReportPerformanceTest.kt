package es.topophone.core.formats.csv

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.StakeoutReport
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

/**
 * Con 1.000 puntos replanteados, el resumen y el CSV del informe se generan en menos de 2 s (S019, RNF de la spec). La JVM sirve de
 * cota; el móvil es de la demo.
 */
class StakeoutReportPerformanceTest {

    private val project = Project(
        id = "p1",
        name = "Parcelaria",
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

    /** 1.000 replanteos: 500 puntos de diseño replanteados dos veces cada uno, con y sin cota y algunos sin evaluar. */
    private val points = List(POINTS) { i ->
        val lat = 40.4 + (i % 100) * 1e-4
        val lon = -3.7 + (i / 100) * 1e-4
        val coordinates = PointCoordinates(GeodeticPosition(lat, lon, 698.066), 440_000.123456 + i, 4_474_000.654321 + i, 650.0 + i * 1e-3)
        val design = coordinates.copy(easting = coordinates.easting!! + 0.01, northing = coordinates.northing!! - 0.02)
        val withElevation = i % 5 != 0
        val evaluated = i % 7 != 0
        val ref = StakeoutRef(
            designId = "design-${i / 2}",
            designName = "D${i / 2}",
            design = design,
            dEastM = -0.01,
            dNorthM = 0.02,
            dHorizontalM = 0.0224,
            dHeightM = if (withElevation) (i % 9 - 4) * 0.01 else null,
            designElevationM = if (withElevation) coordinates.height else null,
            toleranceHM = if (evaluated) 0.025 else null,
            toleranceVM = if (evaluated) 0.03 else null,
            withinTolerance = if (evaluated) i % 3 != 0 else null,
        )
        Point.measured(
            id = "id-$i",
            projectId = "p1",
            name = "D${i / 2}_stkd" + if (i % 2 == 0) "" else "2",
            description = "Bordillo, \"tramo $i\"",
            measurement = Measurement(
                antenna = GeodeticPosition(lat, lon, 700.0),
                poleHeightM = 1.8,
                quality = PointQuality("FIX", 20, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, false),
                rawNmea = "",
                outOfTolerance = emptySet(),
                measuredFromMs = 1_792_346_614_419 + i * 1_000L,
                measuredToMs = 1_792_346_614_619 + i * 1_000L,
                coordinates = coordinates,
            ),
            createdAtMs = i.toLong(),
            staked = ref,
        )
    }

    @Test
    fun `S019 RNF con 1000 puntos replanteados el resumen y el CSV se generan en menos de 2 s`() {
        val (text, elapsed) = measureTimedValue {
            val report = StakeoutReport.of(points)
            val bytes = ByteArrayOutputStream()
            BufferedWriter(OutputStreamWriter(bytes, Charsets.UTF_8)).use {
                StakeoutReportCsv.write(report.rows, project, ColumnSeparator.SEMICOLON, DecimalSeparator.COMMA, it)
            }
            report.summary.designPoints shouldBe POINTS / 2
            bytes.toString(Charsets.UTF_8)
        }

        text.split("\r\n").size shouldBe POINTS + 2 // cabecera, replanteos y la línea vacía del CRLF final
        elapsed.inWholeMilliseconds shouldBeLessThan 2_000L
    }

    private companion object {
        const val POINTS = 1_000
    }
}

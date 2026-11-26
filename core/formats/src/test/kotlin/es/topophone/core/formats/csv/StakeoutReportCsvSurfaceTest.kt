package es.topophone.core.formats.csv

import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.StakeoutReport
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los puntos replanteados contra la superficie en el informe y su CSV: sin planta y por su nombre (S062/RF-21, S019/RF-21). */
class StakeoutReportCsvSurfaceTest {

    private val project = Project(
        id = "p1",
        name = "Obra",
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

    private fun surfacePoint(id: String, name: String, createdAtMs: Long): Point {
        val measured = PointCoordinates(GeodeticPosition(40.41681, -3.70379, 663.4), 440_123.45, 4_474_321.98, 612.4)
        val ref = StakeoutRef(
            designId = id,
            designName = "explanacion",
            design = measured.copy(height = 612.44),
            dEastM = 0.0,
            dNorthM = 0.0,
            dHorizontalM = 0.0,
            dHeightM = -0.04,
            designElevationM = 612.44,
            toleranceHM = null,
            toleranceVM = 0.03,
            withinTolerance = false,
            source = StakeSource.Surface("explanacion"),
            elevationSource = ElevationSource.SURFACE,
            surfaceName = "explanacion",
            surfaceImportedAtMs = 1_790_000_000_000L,
            surfaceElevationM = 612.34,
            verticalOffsetM = 0.1,
        )
        val measurement = Measurement(
            antenna = measured.ground,
            poleHeightM = 2.0,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = measured,
        )
        return Point.measured(id, "p1", name, "", measurement, createdAtMs, ref)
    }

    @Test
    fun `S062 RF-21 una fila de superficie lleva la superficie como diseno y vacias la planta y el numero`() {
        val report = StakeoutReport.of(listOf(surfacePoint("a", "SUP1", 1_000)))

        val line = StringBuilder().also {
            StakeoutReportCsv.write(report.rows, project, ColumnSeparator.COMMA, DecimalSeparator.POINT, it)
        }.toString().split("\r\n")[1]

        line shouldBe
            "explanacion,,SUP1,,,,612.4400,440123.4500,4474321.9800,612.4000,,,,-0.0400,,0.0300,false,true,FIX,2026-10-18T18:03:34Z," +
            ",,,,,,,,,,,,,,,,,SURFACE,explanacion,612.3400,0.1000"
    }

    @Test
    fun `S062 RF-21 los SUP van por su nombre y cada uno es el ultimo de su diseno`() {
        val report = StakeoutReport.of(
            listOf(surfacePoint("z", "SUP10", 3_000), surfacePoint("m", "SUP2", 2_000), surfacePoint("a", "SUP1", 1_000)),
        )

        report.rows.map { it.point.name } shouldBe listOf("SUP1", "SUP2", "SUP10")
        report.rows.all { it.last } shouldBe true
    }

    @Test
    fun `S062 RF-17 los SUP no cuentan en las desviaciones horizontales del resumen`() {
        val summary = StakeoutReport.of(listOf(surfacePoint("a", "SUP1", 1_000))).summary

        summary.horizontalMaxM shouldBe null
        summary.verticalCount shouldBe 1
        summary.outside shouldBe 1
    }
}

package es.topophone.core.formats.csv

import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.LineStakeoutMode
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

/** Los puntos replanteados de una línea del proyecto en el informe de replanteo, con la columna Line (S027/RF-14). */
class StakeoutReportCsvLineTest {

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

    private val design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.42), 440_000.1234, 4_470_000.5, 700.0)
    private val measured = PointCoordinates(GeodeticPosition(40.41681, -3.70379, 699.44), 440_000.14, 4_470_000.49, 700.02)

    private fun point(name: String, createdAt: Long, ref: StakeoutRef): Point = Point.measured(
        id = "id-$name",
        projectId = "p1",
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.41681, -3.70379, 701.0),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.009, 0.014, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = createdAt,
            measuredToMs = createdAt + 200,
            coordinates = measured,
        ),
        createdAtMs = createdAt,
        staked = ref,
    )

    private fun ref(source: StakeSource, designId: String, designName: String, mode: LineStakeoutMode? = null, chainage: Double? = null) =
        StakeoutRef(
            designId = designId,
            designName = designName,
            design = design,
            dEastM = 0.0156,
            dNorthM = -0.0100,
            dHorizontalM = 0.0185,
            dHeightM = 0.02,
            designElevationM = 700.0,
            toleranceHM = 0.025,
            toleranceVM = 0.03,
            withinTolerance = true,
            source = source,
            mode = mode,
            chainageM = chainage,
            lateralOffsetM = if (chainage != null) -1.5 else null,
        )

    private fun cells(text: String): List<Map<String, String>> {
        val lines = text.removeSuffix("\r\n").split("\r\n")
        val header = lines.first().split(",")
        return lines.drop(1).map { header.zip(it.split(",")).toMap() }
    }

    private fun csv(points: List<Point>): String = StringBuilder().also {
        StakeoutReportCsv.write(StakeoutReport.of(points).rows, project, ColumnSeparator.COMMA, DecimalSeparator.POINT, it)
    }
        .toString()

    private val lineSource = StakeSource.ProjectLine("l1", "BORD_1")

    @Test
    fun `S027 RF-14 el informe lleva la columna Line tras el desplazamiento lateral`() {
        val header = csv(emptyList()).removeSuffix("\r\n").split(",")

        header.indexOf("Line") shouldBe header.indexOf("Lateral offset") + 1
        header.last() shouldBe "Vertical offset"
    }

    @Test
    fun `S027 RF-14 un punto replanteado de una linea lleva su nombre en Line y deja vacias las columnas del dibujo`() {
        val rows = cells(csv(listOf(point("BORD_1_1_stkd", 1_000, ref(lineSource, "line:l1", "BORD_1", LineStakeoutMode.ALONG, 45.25)))))

        val row = rows.single()
        row["Line"] shouldBe "BORD_1"
        row["Drawing"] shouldBe ""
        row["Layer"] shouldBe ""
        row["Element type"] shouldBe ""
        row["Mode"] shouldBe "ALONG"
        row["Chainage"] shouldBe "45.2500"
        row["Lateral offset"] shouldBe "-1.5000"
        row["Design name"] shouldBe "BORD_1"
    }

    @Test
    fun `S027 RF-14 un replanteo de un dibujo y uno de un punto dejan vacia la columna Line`() {
        val drawing =
            ref(StakeSource.CadElement("calle.dxf", "Eje", CadEntityKind.POLYLINE), "cad:l1:7:3", "Eje", LineStakeoutMode.TO_LINE, 12.0)
        val plain = ref(StakeSource.ProjectPoint, "P1", "P1")

        val rows = cells(csv(listOf(point("Eje_1_stkd", 1_000, drawing), point("P1_stkd", 2_000, plain))))

        rows.map { it["Line"] } shouldBe listOf("", "")
        rows.first { it["Staked name"] == "Eje_1_stkd" }["Drawing"] shouldBe "calle.dxf"
    }

    @Test
    fun `S027 RF-14 los replanteos repetidos de una linea son dos filas del mismo diseno y solo el ultimo es el ultimo`() {
        val points = listOf(
            point("BORD_1_1_stkd", 1_000, ref(lineSource, "line:l1", "BORD_1", LineStakeoutMode.ALONG, 10.0)),
            point("BORD_1_2_stkd", 2_000, ref(lineSource, "line:l1", "BORD_1", LineStakeoutMode.ALONG, 20.0)),
        )

        val rows = cells(csv(points))

        rows.map { it["Staked name"] } shouldBe listOf("BORD_1_1_stkd", "BORD_1_2_stkd")
        rows.map { it["Last"] } shouldBe listOf("false", "true")
        rows.map { it["Line"] } shouldBe listOf("BORD_1", "BORD_1")
    }
}

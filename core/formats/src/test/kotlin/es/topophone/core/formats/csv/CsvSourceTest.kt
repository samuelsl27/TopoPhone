package es.topophone.core.formats.csv

import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los puntos simulados salen marcados en la columna «Replay» de «Todas las columnas», sea cual sea la fuente (S022/RF-19; plan D3). */
class CsvSourceTest {

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

    private fun point(source: PositionSource) = Point.measured(
        id = "id-${source.name}",
        projectId = "p1",
        name = source.name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416815094, -3.703533016, 701.357),
            poleHeightM = 0.0,
            quality = PointQuality(
                "SINGLE",
                9,
                null,
                null,
                2.5,
                null,
                MeasurementMode.INSTANT,
                1,
                0,
                fromReplay = source.simulated,
                source = source,
            ),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_792_346_614_419,
            measuredToMs = 1_792_346_639_419,
            coordinates = PointCoordinates(
                GeodeticPosition(40.416815094, -3.703533016, 699.423),
                440_313.1221,
                4_474_258.8769,
                648.3114,
            ),
        ),
        createdAtMs = 1_792_346_639_419,
    )

    private fun rows(points: List<Point>): List<List<String>> {
        val text = StringBuilder().also { CsvExport.write(points.asSequence(), project, CsvOptions(), it) }.toString()
        return text.removeSuffix("\r\n").split("\r\n").map { it.split(",") }
    }

    @Test
    fun `S022 RF-19 la columna Replay es verdadera con el virtual, la reproduccion y la ubicacion simulada, y falsa con las demas`() {
        val table = rows(PositionSource.entries.map(::point))
        val column = table.first().indexOf("Replay")

        (column > 0) shouldBe true
        val byName = table.drop(1).associate { it[0] to it[column] }
        byName shouldBe mapOf(
            "RECEIVER" to "false",
            "REPLAY" to "true",
            "PHONE" to "false",
            "PHONE_MOCK" to "true",
            "VIRTUAL" to "true",
        )
    }

    @Test
    fun `S022 RF-11 la cabecera y el orden de las columnas no cambian con la fuente de la posicion`() {
        val header = rows(listOf(point(PositionSource.RECEIVER))).first()

        header.slice(21..23) shouldBe listOf("Epochs discarded", "Replay", "Out of tolerance")
        rows(listOf(point(PositionSource.VIRTUAL))).first() shouldBe header
    }
}

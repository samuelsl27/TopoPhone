package es.topophone.core.formats.csv

import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El identificador de la localización es una última columna del CSV con una localización activa (S039/RF-23). */
class CsvExportLocalizationTest {

    private val project = Project(
        id = "p1",
        name = "Calle",
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

    private fun point(name: String, localizationId: String?, code: PointCode? = null) = Point(
        id = "id-$name",
        projectId = "p1",
        name = name,
        description = "",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.4, -3.7, null), 440_000.0, 4_472_000.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = 0,
        deletedAtMs = null,
        code = code,
        localizationId = localizationId,
    )

    private fun csv(points: List<Point>, withLocalization: Boolean, options: CsvOptions = CsvOptions()): List<List<String>> {
        val out = StringBuilder()
        CsvExport.write(
            points.asSequence(),
            project,
            options,
            out,
            attributes = AttributeColumns.of(points),
            withLocalization = withLocalization,
        )
        return out.toString().trimEnd().split("\r\n").map { it.split(options.separator.char) }
    }

    @Test
    fun `S039 RF-23 con localizacion activa la ultima columna es su identificador`() {
        val rows = csv(listOf(point("1", "loc-1"), point("2", null)), withLocalization = true)

        rows.first().last() shouldBe "Localization id"
        rows[1].last() shouldBe "loc-1"
        rows[2].last() shouldBe ""
        rows.forEach { it.size shouldBe rows.first().size }
    }

    @Test
    fun `S039 RF-23 sin localizacion activa el fichero es el de siempre`() {
        val plain = csv(listOf(point("1", "loc-1")), withLocalization = false)

        plain.first().last() shouldBe CsvExportHeaders.lastAll
        ("Localization id" in plain.first()) shouldBe false
    }

    @Test
    fun `S039 RF-23 la columna va despues de los atributos de los codigos`() {
        val code = PointCode("ARQ", "", 0x1E88E5, CodeSymbol.CIRCLE, listOf(PointAttribute("Material", AttributeType.LIST, "PVC")))

        val header = csv(listOf(point("1", "loc-1", code)), withLocalization = true).first()

        header.takeLast(2) shouldBe listOf("Material", "Localization id")
    }

    @Test
    fun `S039 RF-23 con un formato de pocas columnas tambien se anade al final`() {
        val rows = csv(listOf(point("1", "loc-1")), withLocalization = true, options = CsvOptions(format = ExportFormat.PNEZD))

        rows.first().last() shouldBe "Localization id"
        rows[1].last() shouldBe "loc-1"
    }

    /** La última cabecera de «Todas las columnas» sin localización: no cambia al añadir la columna. */
    private object CsvExportHeaders {
        val lastAll: String get() = ExportColumn.entries.last().header
    }
}

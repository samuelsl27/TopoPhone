package es.topophone.core.formats.localization

import es.topophone.core.model.localization.Localization
import es.topophone.core.model.localization.LocalizationOrigin
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.localization.PairResidualValues
import es.topophone.core.model.localization.VerticalKind
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import java.util.Locale
import org.junit.jupiter.api.Test

/** El informe CSV de una localización: parámetros, pares y residuos, con `Locale.ROOT` (S039/RF-25; RNF-T9). */
class LocalizationReportCsvTest {

    private val project = Project(
        id = "p1",
        name = "Calle, \"Mayor\" 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val pairs = listOf(
        LocalizationPair("a", "p1", "Clavo 1", 440_290.123456, 4_474_257.0, 648.3, 440_390.1, 4_474_207.0, 648.55),
        LocalizationPair("b", "p2", "Clavo 2", 440_500.0, 4_474_300.0, null, 440_600.0, 4_474_250.0, null, useVertical = false),
    )

    private val localization = Localization(
        id = "l1",
        projectId = "p1",
        status = LocalizationStatus.ACTIVE,
        origin = LocalizationOrigin.PAIRS,
        name = null,
        createdAtMs = 1,
        appliedAtMs = 2,
        fixScale = false,
        residualLimitM = 0.03,
        scaleLimit = 0.001,
        crsText = "SPAIN|ETRS89_UTM30",
        heightSystem = "ORTHOMETRIC",
        params = LocalizationParams(
            centreE = 440_395.0,
            centreN = 4_474_278.0,
            knownCentreE = 440_495.0,
            knownCentreN = 4_474_228.0,
            rotationRad = 0.0035,
            scale = 1.00002,
            hasHorizontal = true,
            verticalKind = VerticalKind.OFFSET,
            dh = 0.25,
        ),
        rmsHorizontal = 0.0123,
        rmsVertical = 0.0045,
        pairs = pairs,
        residuals = mapOf("a" to PairResidualValues(0.003, -0.004, 0.0005), "b" to PairResidualValues(-0.003, 0.004, null)),
    )

    private fun report(l: Localization = localization): List<List<String>> {
        val out = StringBuilder()
        LocalizationReportCsv.write(l, project, out)
        return out.toString().trimEnd().split("\r\n").map { line -> line.split(',') }
    }

    @Test
    fun `S039 RF-25 el informe lleva los parametros y despues los pares con sus residuos`() {
        val text = StringBuilder().also { LocalizationReportCsv.write(localization, project, it) }.toString()

        text.contains("Translation easting (centroid),100.0000\r\n") shouldBe true
        text.contains("Rotation (rad),0.003500000000\r\n") shouldBe true
        text.contains("Scale,1.00002000\r\n") shouldBe true
        text.contains("Scale (ppm),20.000\r\n") shouldBe true
        text.contains("Vertical,OFFSET\r\n") shouldBe true
        text.contains("Vertical offset,0.2500\r\n") shouldBe true
        text.contains("RMS horizontal,0.0123\r\n") shouldBe true
        val tableStart = text.indexOf("\r\n\r\n")
        text.substring(tableStart + 4).startsWith("Point,CRS easting,CRS northing") shouldBe true
    }

    @Test
    fun `S039 RF-25 cada par es una fila con el residuo horizontal y lo que falta queda vacio`() {
        val rows = report()
        val header = rows.indexOfFirst { it.first() == "Point" }
        val a = rows[header + 1]
        val b = rows[header + 2]

        a[0] shouldBe "Clavo 1"
        a[1] shouldBe "440290.1235"
        a[3] shouldBe "648.3000"
        a[10] shouldBe "0.0030"
        a[12] shouldBe "0.0050"
        a[13] shouldBe "0.0005"
        b[3] shouldBe ""
        b[8] shouldBe "false"
        b[13] shouldBe ""
    }

    @Test
    fun `S039 RF-25 los numeros no dependen del idioma del telefono`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            val text = StringBuilder().also { LocalizationReportCsv.write(localization, project, it) }.toString()

            text.contains("Scale,1.00002000\r\n") shouldBe true
            text.contains(",") shouldBe true
            text.contains("1,00002") shouldBe false
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `S039 RF-25 un nombre con comas o comillas va entre comillas`() {
        val text = StringBuilder().also { LocalizationReportCsv.write(localization, project, it) }.toString()

        text.startsWith("Project,\"Calle, \"\"Mayor\"\" 12\"\r\n") shouldBe true
    }

    @Test
    fun `S039 RF-25 sin calcular el informe no lleva parametros ni residuos`() {
        val draft = localization.copy(
            params = null,
            rmsHorizontal = null,
            rmsVertical = null,
            residuals = emptyMap(),
            status = LocalizationStatus.DRAFT,
        )

        val rows = report(draft)

        rows.none { it.first() == "Scale" } shouldBe true
        rows.first { it.first() == "RMS horizontal" }[1] shouldBe ""
        val header = rows.indexOfFirst { it.first() == "Point" }
        rows[header + 1][10] shouldBe ""
    }

    @Test
    fun `S039 RF-25 en pies las longitudes van en la unidad del proyecto`() {
        val feet = project.copy(linearUnit = LinearUnit.INTERNATIONAL_FOOT)
        val out = StringBuilder().also { LocalizationReportCsv.write(localization, feet, it) }.toString()

        out.contains("Units,ft\r\n") shouldBe true
        out.contains("Translation easting (centroid),328.0840\r\n") shouldBe true
    }
}

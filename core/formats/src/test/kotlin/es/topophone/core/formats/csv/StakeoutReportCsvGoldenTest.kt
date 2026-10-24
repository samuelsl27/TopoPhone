package es.topophone.core.formats.csv

import es.topophone.core.model.point.StakeoutReport
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Ficheros dorados del CSV del informe de replanteo (S019/RF-21, RF-22, RF-23; constitución nº 2). `StakeoutReportCsv` debe
 * escribir exactamente `fixtures/formatos/csv/informe_replanteo_*.csv`, que `tools/formatos/test_informe_replanteo_exportado.py`
 * lee con el módulo `csv` de Python y compara con `fixtures/replanteo/replanteo-informe.csv`, calculado con pyproj. Con
 * `TOPOPHONE_REGENERAR_DORADOS=1` se vuelven a escribir.
 */
class StakeoutReportCsvGoldenTest {

    private val folder = File(
        generateSequence(File("").absoluteFile) { it.parentFile }.first {
            File(it, "settings.gradle.kts").exists() &&
                File(it, "fixtures").isDirectory
        },
        "fixtures/formatos/csv",
    )

    private fun project(crs: ProjectCrs) = Project(
        id = "p1",
        name = "Dorados",
        author = "",
        description = "",
        crs = crs,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun written(fixtureProject: String, crs: ProjectCrs, separator: ColumnSeparator, decimal: DecimalSeparator): ByteArray {
        val report = StakeoutReport.of(ReplanteoFixtures.points(fixtureProject))
        return StringBuilder().also { StakeoutReportCsv.write(report.rows, project(crs), separator, decimal, it) }.toString()
            .toByteArray(Charsets.UTF_8)
    }

    private val files = mapOf(
        "informe_replanteo_utm30.csv" to { written("UTM30", ProjectCrs.ETRS89_UTM30, ColumnSeparator.COMMA, DecimalSeparator.POINT) },
        "informe_replanteo_canarias_puntoycoma_coma.csv" to {
            written("UTM28", ProjectCrs.REGCAN95_UTM28, ColumnSeparator.SEMICOLON, DecimalSeparator.COMMA)
        },
        "informe_replanteo_geografico_tabulador_punto.csv" to {
            written("GEOGRAFICO", ProjectCrs.ETRS89_GEOGRAPHIC, ColumnSeparator.TAB, DecimalSeparator.POINT)
        },
    )

    @Test
    fun `S019 RF-21, RF-22 y RF-23 StakeoutReportCsv escribe exactamente los ficheros dorados`() {
        if (System.getenv("TOPOPHONE_REGENERAR_DORADOS") == "1") {
            folder.mkdirs()
            files.forEach { (name, bytes) -> File(folder, name).writeBytes(bytes()) }
        }

        val different = files.filter { (name, bytes) -> !File(folder, name).readBytes().contentEquals(bytes()) }.keys
        different.shouldBeEmpty()
    }
}

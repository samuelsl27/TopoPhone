package es.topophone.core.formats.csv

import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Exportar CSV con un SC de la biblioteca (S034/RF-28; S012/RF-10). */
class LibraryCsvExportTest {
    private fun definition(projected: Boolean) = CrsDefinition(
        epsg = if (projected) 2154 else 4258, name = "n", projected = projected, datumCode = 1, datumName = "d",
        ellipsoidSemiMajorM = 6378137.0, ellipsoidInverseFlattening = 298.257222101, primeMeridianDeg = 0.0,
        method = if (projected) "lcc2" else "geo", parameters = emptyMap(), unitMetres = 1.0, area = null, frame = "ETRS89",
        transformationName = "t", transformationAccuracyM = null, transformationArea = null, transformationSteps = "null",
    )

    @Test
    fun `S034 RF-28 un SC proyectado ofrece todos los formatos de CSV y uno geografico solo los que no usan Este y Norte`() {
        CsvExport.formatsFor(LibraryCrs(definition(projected = true))) shouldBe ExportFormat.entries
        CsvExport.formatsFor(LibraryCrs(definition(projected = false))) shouldBe listOf(ExportFormat.ALL, ExportFormat.CUSTOM)
    }
}

package es.topophone.core.formats.dxf.export

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/** Exportar a DXF con un proyecto en un SC de la biblioteca: coordenadas locales (Este y Norte del proyecto) o globales (S057/RF-21; S029/RF-11 a RF-14). */
class DxfExportLibraryTest {
    private val p1 = DxfTestData.point("P1", 440_000.1234567, 4_474_000.7654321, 650.1236, DxfTestData.code("ARBOL", 0x008000), "Roble")
    private val p2 = DxfTestData.point("P2", 440_010.5, 4_474_010.25, null, null, "")
    private val points = listOf(p1, p2)
    private val lines = listOf(DxfTestData.line("L1", false, null, p1, p2))

    private fun library(epsg: Int, at: GeoPoint = GeoPoint(40.4, -3.7)) = LibraryCrs(
        (CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at) as CrsBuildResult.Ready).definition,
    )

    private fun bytes(build: DxfBuild): List<Byte> = ByteArrayOutputStream().also {
        DxfWriter.write(build.document, it)
    }.toByteArray().toList()

    private fun build(project: es.topophone.core.model.project.Project, coordinates: DxfCoordinates) =
        DxfDocumentBuilder.build(points, lines, points, project, DxfExportOptions(coordinates = coordinates))

    @Test
    fun `S057 RF-21 en locales un proyecto de la biblioteca proyectado da el mismo fichero que uno de Espana`() {
        val spain = build(DxfTestData.project(), DxfCoordinates.LOCAL)
        val lib = build(DxfTestData.project(crs = library(25830)), DxfCoordinates.LOCAL)

        bytes(lib) shouldBe bytes(spain)
        lib.summary.coordinates shouldBe DxfCoordinates.LOCAL
        lib.summary.unit shouldBe DxfUnit.METRE
    }

    @Test
    fun `S057 RF-21 en locales la unidad del dibujo es la del proyecto, no la del SC`() {
        val lib = build(DxfTestData.project(crs = library(25830), unit = LinearUnit.INTERNATIONAL_FOOT), DxfCoordinates.LOCAL)

        lib.summary.unit shouldBe DxfUnit.INTERNATIONAL_FOOT
    }

    @Test
    fun `S057 RF-21 en globales un proyecto de la biblioteca da longitud y latitud del datum de las correcciones`() {
        val spain = build(DxfTestData.project(), DxfCoordinates.GLOBAL)
        val lib = build(DxfTestData.project(crs = library(25830)), DxfCoordinates.GLOBAL)

        bytes(lib) shouldBe bytes(spain)
        lib.summary.unit shouldBe DxfUnit.DEGREE
    }

    @Test
    fun `S057 RF-21 un proyecto geografico de la biblioteca solo tiene coordenadas globales`() {
        val geographic = library(4326)

        val build = build(DxfTestData.project(crs = geographic), DxfCoordinates.LOCAL)

        build.summary.coordinates shouldBe DxfCoordinates.GLOBAL
        build.summary.unit shouldBe DxfUnit.DEGREE
    }
}

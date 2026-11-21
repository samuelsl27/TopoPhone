package es.topophone.core.formats.kml.export

import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Exportar a KML con un proyecto en un SC de la biblioteca: longitud y latitud son las del datum de las correcciones, como siempre (S057/RF-17, RF-24). */
class KmlExportLibraryTest {
    private val builder = KmlExportBuilder()

    private fun library(epsg: Int, corrections: CorrectionsDatum, at: GeoPoint = GeoPoint(40.4, -3.7)) = LibraryCrs(
        (CrsCatalog.bundled.build(epsg, corrections, at) as CrsBuildResult.Ready).definition,
        corrections,
    )

    @Test
    fun `S057 RF-24 un proyecto de la biblioteca da las mismas marcas con las mismas geograficas que uno de Espana`() {
        val points = listOf(point("1", description = "Una farola"), point("2"))

        val spain = builder.build(points, project(), KmlExportOptions(), allPoints = points)
        val lib = builder.build(points, project(crs = library(25830, CorrectionsDatum.ETRS89)), KmlExportOptions(), allPoints = points)

        lib.summary shouldBe spain.summary
        lib.document.folders.flatMap { it.marks }.map { it.geometry } shouldBe
            spain.document.folders.flatMap { it.marks }.map { it.geometry }
    }

    @Test
    fun `S057 RF-17 un proyecto de la biblioteca con otro datum de correcciones escribe las geograficas del punto, sin transformarlas`() {
        val point = point("1")
        val wgs84 = library(32630, CorrectionsDatum.ITRF_WGS84)

        val export = builder.build(listOf(point), project(crs = wgs84), KmlExportOptions(), allPoints = listOf(point))

        val vertex = (export.document.folders.single().marks.single().geometry as KmlGeometry.Point).vertex
        vertex.lon shouldBe point.coordinates.ground.longitudeDeg
        vertex.lat shouldBe point.coordinates.ground.latitudeDeg
    }

    @Test
    fun `S057 RF-24 la longitud y la latitud se escriben con 9 decimales en un proyecto de la biblioteca`() {
        KmlNumber.coordinate(-3.703790123456789) shouldBe "-3.703790123"
    }
}

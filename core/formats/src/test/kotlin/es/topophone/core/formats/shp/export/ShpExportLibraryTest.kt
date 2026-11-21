package es.topophone.core.formats.shp.export

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.shp.ShpCrsCatalog
import es.topophone.core.formats.shp.export.ShpExportSamples.point
import es.topophone.core.formats.shp.export.ShpExportSamples.project
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CrsWkt
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Exportar a Shapefile con un proyecto en un SC de la biblioteca: el `.prj` es su WKT1 de ESRI y se vuelve a reconocer al importarlo (S057/RF-23, RF-25, RF-26). */
class ShpExportLibraryTest {
    private val builder = ShpExportBuilder()
    private val catalog = CrsCatalog.bundled

    private fun definitionOf(epsg: Int): CrsDefinition {
        val entry = catalog.crsByEpsg(epsg).shouldNotBeNull()
        val datum = catalog.datums.getValue(entry.datumCode)
        val option = catalog.optionsFor(entry.datumCode, es.topophone.core.geodesy.catalog.CorrectionFrame.ETRS89).first()
        return es.topophone.core.geodesy.catalog.CrsDefinitions.of(entry, datum, option)
    }

    private fun library(epsg: Int, corrections: CorrectionsDatum = CorrectionsDatum.ETRS89) = LibraryCrs(definitionOf(epsg), corrections)

    @Test
    fun `S057 RF-23 el prj de un proyecto de la biblioteca es el WKT1 de ESRI de su SC`() {
        val crs = library(2154)

        val export = builder.build(listOf(point("1")), project(crs = crs))

        export.summary.epsg shouldBe 2154
        export.summary.prjMissing shouldBe false
        export.layers.first().prj shouldBe CrsWkt.bundled.prj(2154)
        export.layers.first().prj!!.startsWith("PROJCS[\"RGF_1993_Lambert_93\"") shouldBe true
    }

    @Test
    fun `S057 RF-23 un SC de la biblioteca que tambien es de Espana lleva el prj de siempre`() {
        ShpPrjDefinitions().of(library(25830)) shouldBe ShpPrjDefinitions().of(ProjectCrs.ETRS89_UTM30)
    }

    @Test
    fun `S057 RF-23 en geograficas el prj es el del datum de las correcciones del proyecto`() {
        ShpPrjDefinitions.geographicEpsg(library(2154)) shouldBe 4258
        ShpPrjDefinitions.geographicEpsg(library(32630, CorrectionsDatum.ITRF_WGS84)) shouldBe 4326
        ShpPrjDefinitions().geographicOf(
            library(32630, CorrectionsDatum.ITRF_WGS84),
        ).shouldNotBeNull().startsWith("GEOGCS[\"GCS_WGS_1984\"") shouldBe
            true
    }

    @Test
    fun `S057 RF-26 un SC sin definicion no escribe el prj y se avisa`() {
        val unknown = library(2154).let { it.copy(definition = it.definition.copy(epsg = 999_999, name = "Sin WKT")) }

        ShpPrjDefinitions().of(unknown).shouldBeNull()
        builder.build(listOf(point("1")), project(crs = unknown)).summary.prjMissing shouldBe true
    }

    @Test
    fun `S057 RF-23 el prj de cada SC de la biblioteca vuelve a reconocerse al importarlo con el mismo SC`() {
        val failures = catalog.crs.mapNotNull { crs ->
            val prj = CrsWkt.bundled.prj(crs.epsg) ?: return@mapNotNull "EPSG:${crs.epsg}: sin prj"
            when (val resolved = ShpCrsCatalog.resolve(prj)) {
                is GpkgCrs.Library -> "EPSG:${crs.epsg}: reconocido como ${resolved.epsg}".takeIf { resolved.epsg != crs.epsg }
                is GpkgCrs.Unavailable -> "EPSG:${crs.epsg}: no disponible".takeIf { crs.epsg !in resolved.candidates.map { it.epsg } }
                is GpkgCrs.Supported -> null // un SC de España: la importación lo reconoce como tal
                GpkgCrs.Undeclared -> "EPSG:${crs.epsg}: sin sistema"
            }
        }
        java.io.File("build/shp-roundtrip-failures.txt").writeText(failures.joinToString("\n"))

        failures.shouldBeEmpty()
    }
}

package es.topophone.core.formats.gpkg

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El SC de la biblioteca declarado en un GeoPackage exportado: su código EPSG y su definición WKT1 de pyproj (S057/RF-22, RF-26; S053/RF-6). */
class GpkgSrsDefinitionsLibraryTest {

    private fun library(epsg: Int, corrections: CorrectionsDatum = CorrectionsDatum.ETRS89) = LibraryCrs(
        (CrsCatalog.bundled.build(epsg, corrections, GeoPoint(48.0, 2.0)) as CrsBuildResult.Ready).definition,
        corrections,
    )

    @Test
    fun `S057 RF-22 un SC de la biblioteca se declara con su codigo EPSG y su definicion completa`() {
        val srs = GpkgSrsDefinitions.of(library(2154))

        srs.srsId shouldBe 2154
        srs.organization shouldBe "EPSG"
        srs.organizationCode shouldBe 2154
        srs.name shouldBe "RGF93 v1 / Lambert-93"
        srs.definition.startsWith("PROJCS[\"RGF93") shouldBe true
        srs.definition.endsWith("AUTHORITY[\"EPSG\",\"2154\"]]") shouldBe true
        GpkgSrsDefinitions.isExpressible(library(2154)) shouldBe true
    }

    @Test
    fun `S057 RF-22 un SC de la biblioteca que tambien es de Espana lleva la misma definicion de siempre`() {
        GpkgSrsDefinitions.of(library(25830)) shouldBe GpkgSrsDefinitions.of(ProjectCrs.ETRS89_UTM30)
    }

    @Test
    fun `S057 RF-22 las geograficas de los puntos son las del datum de las correcciones del proyecto`() {
        GpkgSrsDefinitions.geographicOf(library(2154)).srsId shouldBe 4258
        GpkgSrsDefinitions.geographicOf(library(32630, CorrectionsDatum.ITRF_WGS84)).srsId shouldBe 4326
        GpkgSrsDefinitions.geographicOf(library(4326, CorrectionsDatum.ITRF_WGS84)).srsId shouldBe 4326
    }

    @Test
    fun `S057 RF-26 un SC cuya definicion no se pudo expresar declara undefined y se avisa`() {
        val unknown = library(2154).let { it.copy(definition = it.definition.copy(epsg = 999_999, name = "Sistema sin WKT")) }

        GpkgSrsDefinitions.isExpressible(unknown) shouldBe false
        GpkgSrsDefinitions.of(unknown).definition shouldBe "undefined"
        GpkgSrsDefinitions.of(unknown).organizationCode shouldBe 999_999
        GpkgSrsDefinitions.isExpressible(ProjectCrs.ETRS89_UTM30) shouldBe true
    }

    @Test
    fun `S057 RF-22 cada SC de la biblioteca que se puede elegir tiene definicion exportable con su AUTHORITY`() {
        var checked = 0
        for (epsg in listOf(2154, 3857, 4326, 25833, 27700, 32630, 2263, 31467, 2100, 3844)) {
            val definition = when (val result = CrsCatalog.bundled.build(epsg, CorrectionsDatum.ITRF_WGS84, GeoPoint(40.0, -3.0))) {
                is CrsBuildResult.Ready -> result.definition
                is CrsBuildResult.NeedsGrids -> result.definition
                is CrsBuildResult.Unavailable -> continue
            }

            GpkgSrsDefinitions.of(LibraryCrs(definition, CorrectionsDatum.ITRF_WGS84)).definition
                .endsWith("AUTHORITY[\"EPSG\",\"$epsg\"]]") shouldBe true
            checked++
        }
        (checked >= 8) shouldBe true
    }
}

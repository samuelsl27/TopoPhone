package es.topophone.core.formats.shp.export

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.shp.ShpCrsCatalog
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El `.prj` de cada Shapefile exportado: el WKT de ESRI de pyproj de cada sistema de la app y de sus geográficas (S055/RF-15, RF-16). */
class ShpPrjDefinitionsTest {
    private val definitions = ShpPrjDefinitions()

    private fun fixture(epsg: Int) = ShpExportFixtures.file("prj/$epsg.prj").readText(Charsets.US_ASCII)

    @Test
    fun `S055 RF-15 cada sistema de la app tiene el prj que genera pyproj en tools`() {
        for (crs in ProjectCrs.entries) {
            definitions.of(crs) shouldBe fixture(crs.epsg)
        }
    }

    @Test
    fun `S055 RF-15 las geograficas del datum son ETRS89 o REGCAN95 y un proyecto ED50 exporta ETRS89`() {
        definitions.geographicOf(ProjectCrs.ETRS89_UTM30) shouldBe fixture(4258)
        definitions.geographicOf(ProjectCrs.ETRS89_GEOGRAPHIC) shouldBe fixture(4258)
        definitions.geographicOf(ProjectCrs.REGCAN95_UTM28) shouldBe fixture(4081)
        definitions.geographicOf(ProjectCrs.REGCAN95_GEOGRAPHIC) shouldBe fixture(4081)
        definitions.geographicOf(ProjectCrs.ED50_UTM30) shouldBe fixture(4258)
    }

    @Test
    fun `S055 RF-15 todos los prj son WKT de ESRI de una linea y en ASCII, sin autoridad ni ejes`() {
        for (crs in ProjectCrs.entries) {
            val text = definitions.of(crs)!!
            (text.startsWith("PROJCS[") || text.startsWith("GEOGCS[")) shouldBe true
            text.all { it.code in 0x20..0x7E } shouldBe true
            text.contains("AUTHORITY") shouldBe false
            text.contains("AXIS") shouldBe false
        }
    }

    @Test
    fun `S055 RF-15 los sistemas proyectados llevan PROJCS y las geograficas GEOGCS`() {
        for (crs in ProjectCrs.entries) {
            definitions.of(crs)!!.startsWith(if (crs.isUtm) "PROJCS[" else "GEOGCS[") shouldBe true
            definitions.geographicOf(crs)!!.startsWith("GEOGCS[") shouldBe true
        }
    }

    @Test
    fun `S055 RF-15 el lector de S030 reconoce cada prj como el sistema del proyecto`() {
        for (crs in ProjectCrs.entries) {
            val resolved = ShpCrsCatalog.resolve(definitions.of(crs))

            (resolved as GpkgCrs.Supported).epsg shouldBe crs.epsg
        }
    }

    @Test
    fun `S055 RF-15 el lector de S030 reconoce las geograficas de cada proyecto como ETRS89 o REGCAN95`() {
        for (crs in ProjectCrs.entries) {
            val resolved = ShpCrsCatalog.resolve(definitions.geographicOf(crs)) as GpkgCrs.Supported

            resolved.epsg shouldBe ShpPrjDefinitions.geographicEpsg(crs)
        }
    }

    @Test
    fun `S055 RF-16 un sistema sin definicion da null`() {
        val empty = ShpPrjDefinitions(emptyMap())

        empty.of(ProjectCrs.ETRS89_UTM30) shouldBe null
        empty.geographicOf(ProjectCrs.ETRS89_UTM30) shouldBe null
    }

    @Test
    fun `S055 RF-16 con la definicion de un solo sistema los demas dan null`() {
        val one = ShpPrjDefinitions(mapOf(ProjectCrs.ETRS89_UTM30.epsg to "PROJCS[\"x\"]"))

        one.of(ProjectCrs.ETRS89_UTM30) shouldBe "PROJCS[\"x\"]"
        one.of(ProjectCrs.ETRS89_UTM29) shouldBe null
        one.geographicOf(ProjectCrs.ETRS89_UTM30) shouldBe null
    }
}

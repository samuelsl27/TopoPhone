package es.topophone.core.geodesy.catalog

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Lectura de las definiciones WKT1 generadas por `tools/geodesia/catalogo_wkt.py` (S057/RF-22, RF-23, RF-25, RF-26). */
class CrsWktTest {
    private val wkt = CrsWkt.bundled

    @Test
    fun `S057 RF-22 la definicion de GeoPackage de UTM 30N de ETRS89 lleva su AUTHORITY de EPSG`() {
        val text = wkt.gpkg(25830).also { it?.shouldStartWith("PROJCS[\"ETRS89 / UTM zone 30N\"") }
        text!! shouldContain "AUTHORITY[\"EPSG\",\"25830\"]"
    }

    @Test
    fun `S057 RF-23 la definicion del prj de Lambert 93 y la de WGS 84 son WKT1 de ESRI`() {
        wkt.prj(2154)!! shouldStartWith "PROJCS[\"RGF_1993_Lambert_93\""
        wkt.prj(4326)!! shouldStartWith "GEOGCS[\"GCS_WGS_1984\""
    }

    @Test
    fun `S057 RF-26 un SC que no esta en la biblioteca no tiene definicion`() {
        wkt.gpkg(999999).shouldBeNull()
        wkt.prj(999999).shouldBeNull()
    }

    @Test
    fun `S057 RF-26 una definicion que PROJ no pudo expresar queda como ausente`() {
        val parsed = CrsWkt.parse("1\tGEOGCS[\"a\"]\tGEOGCS[\"b\"]\n2\t\t\n")

        parsed.gpkg(1) shouldBe "GEOGCS[\"a\"]"
        parsed.gpkg(2).shouldBeNull()
        parsed.prj(2).shouldBeNull()
    }

    @Test
    fun `S057 RF-25 todos los SC del catalogo tienen definicion`() {
        val catalog = CrsCatalog.bundled
        (catalog.crs.count { wkt.gpkg(it.epsg) != null }) shouldBe catalog.crs.size
        catalog.crs.size shouldBeGreaterThan 4500
    }

    @Test
    fun `S057 RF-26 una fila mal formada se rechaza`() {
        assertThrows<IllegalArgumentException> { CrsWkt.parse("1\tsolo dos columnas\n") }
    }
}

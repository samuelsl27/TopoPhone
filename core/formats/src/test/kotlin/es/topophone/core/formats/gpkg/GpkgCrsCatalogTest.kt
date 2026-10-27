package es.topophone.core.formats.gpkg

import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Del EPSG de la capa al sistema de la app, su unidad y «no disponible», contra la lista de pyproj (S048/RF-8 a RF-10). */
class GpkgCrsCatalogTest {

    private val rows = GpkgFixtures.expected("esperado-crs.csv")

    private fun row(epsg: Int) = rows.first { it["epsg"] == epsg.toString() }

    private fun expectedSource(row: Map<String, String>): CadSourceCrs {
        val datum = if (row["datum"] == "WGS84") GeodeticDatum.ETRS89 else GeodeticDatum.valueOf(row.getValue("datum"))
        return if (row["tipo"] == "UTM") CadSourceCrs.Utm(datum, row.getValue("huso").toInt()) else CadSourceCrs.Geographic(datum)
    }

    @Test
    fun `S048 RF-8 cada sistema disponible de la lista de pyproj se reconoce con su datum, su huso y su unidad`() {
        val available = rows.filter { it["estado"] == "DISPONIBLE" }

        available.size shouldBe 10
        for (row in available) {
            val epsg = row.getValue("epsg").toInt()
            val crs = GpkgCrsCatalog.resolve(epsg, "EPSG", epsg).shouldBeInstanceOf<GpkgCrs.Supported>()
            crs.epsg shouldBe epsg
            crs.source shouldBe expectedSource(row)
            crs.unit shouldBe if (row["unidad"] == "m") GpkgCrsUnit.METRE else GpkgCrsUnit.DEGREE
            crs.wgs84 shouldBe false
        }
    }

    @Test
    fun `S048 RF-8 los sistemas de la lista son exactamente los ProjectCrs de la app`() {
        val epsgs = rows.filter { it["estado"] == "DISPONIBLE" }.map { it.getValue("epsg").toInt() }.toSet()

        epsgs shouldBe ProjectCrs.entries.map { it.epsg }.toSet()
        GpkgCrsCatalog.choices.map { it.epsg } shouldBe ProjectCrs.entries.map { it.epsg }
    }

    @Test
    fun `S048 RF-8 WGS 84 se trata como ETRS89 y lo dice`() {
        val crs = GpkgCrsCatalog.resolve(4326, "EPSG", 4326).shouldBeInstanceOf<GpkgCrs.Supported>()

        row(4326)["estado"] shouldBe "WGS84"
        crs.source shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
        crs.unit shouldBe GpkgCrsUnit.DEGREE
        crs.wgs84 shouldBe true
    }

    @Test
    fun `S048 RF-9 los sistemas que la app no ofrece no estan disponibles`() {
        val unavailable = rows.filter { it["estado"] == "NO_DISPONIBLE" }

        unavailable.size shouldBe 7
        for (row in unavailable) {
            val epsg = row.getValue("epsg").toInt()
            GpkgCrsCatalog.resolve(epsg, "EPSG", epsg) shouldBe GpkgCrs.Unavailable("EPSG", epsg)
        }
    }

    @Test
    fun `S048 RF-9 sin sistema o sin definicion el fichero no declara el sistema`() {
        for (row in rows.filter { it["estado"] == "SIN_SISTEMA" }) {
            GpkgCrsCatalog.resolve(row.getValue("epsg").toInt(), "NONE", row.getValue("epsg").toInt()) shouldBe GpkgCrs.Undeclared
        }
        GpkgCrsCatalog.resolve(99999, null, null) shouldBe GpkgCrs.Undeclared
        GpkgCrsCatalog.resolve(25830, "NONE", 25830) shouldBe GpkgCrs.Undeclared
        GpkgCrsCatalog.resolve(0, "undefined", 0) shouldBe GpkgCrs.Undeclared
        GpkgCrsCatalog.resolve(-1, "EPSG", 25830) shouldBe GpkgCrs.Undeclared
    }

    @Test
    fun `S048 RF-8 el srs_id puede ser propio si la fila dice el codigo EPSG`() {
        val crs = GpkgCrsCatalog.resolve(100_001, "epsg", 25830).shouldBeInstanceOf<GpkgCrs.Supported>()

        crs.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
        crs.epsg shouldBe 25830
        GpkgCrsCatalog.resolve(100_002, "ESRI", 102_100) shouldBe GpkgCrs.Unavailable("ESRI", 102_100)
    }

    @Test
    fun `S048 RF-10 la unidad la fija el sistema`() {
        GpkgCrsCatalog.resolveEpsg(25830)!!.unit shouldBe GpkgCrsUnit.METRE
        GpkgCrsCatalog.resolveEpsg(4258)!!.unit shouldBe GpkgCrsUnit.DEGREE
        GpkgCrsCatalog.resolveEpsg(4081)!!.unit shouldBe GpkgCrsUnit.DEGREE
        GpkgCrsCatalog.resolveEpsg(3857) shouldBe null
        GpkgCrsCatalog.choices.all { (it.unit == GpkgCrsUnit.METRE) == (it.source is CadSourceCrs.Utm) } shouldBe true
    }

    @Test
    fun `S048 RF-9 el sistema elegido por el usuario es uno de la app y nunca WGS 84`() {
        val chosen = GpkgCrsCatalog.supported(ProjectCrs.ED50_UTM30)

        chosen.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        chosen.epsg shouldBe 23030
        chosen.wgs84 shouldBe false
        GpkgCrsCatalog.choices.none { it.wgs84 } shouldBe true
    }
}

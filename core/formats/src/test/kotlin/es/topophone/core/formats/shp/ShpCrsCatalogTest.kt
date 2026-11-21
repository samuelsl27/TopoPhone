package es.topophone.core.formats.shp

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import org.junit.jupiter.api.Test

/** Del WKT de un `.prj` al sistema de la app, por autoridad y por estructura, contra pyproj (S030/RF-8, RF-9). */
class ShpCrsCatalogTest {

    private val rows = ShpFixtures.expected("esperado-prj.csv")

    private fun text(path: String) = ShpFixtures.file(path).readText()

    @Test
    fun `S030 RF-8 cada prj de los sistemas de la app se reconoce en los tres estilos con su epsg y su unidad`() {
        val available = rows.filter { it.getValue("estado") == "DISPONIBLE" }

        available.size shouldBe 10 * 3
        for (row in available) {
            val crs = ShpCrsCatalog.resolve(text(row.getValue("archivo"))).shouldBeInstanceOf<GpkgCrs.Supported>()

            crs.epsg shouldBe row.getValue("epsg").toInt()
            crs.unit shouldBe if (row.getValue("unidad") == "m") GpkgCrsUnit.METRE else GpkgCrsUnit.DEGREE
            crs.wgs84 shouldBe false
        }
    }

    @Test
    fun `S030 RF-8 los sistemas reconocidos son exactamente los ProjectCrs de la app`() {
        val epsgs = rows.filter { it.getValue("estado") == "DISPONIBLE" }.map { it.getValue("epsg").toInt() }.toSet()

        epsgs shouldBe ProjectCrs.entries.map { it.epsg }.toSet()
    }

    @Test
    fun `S030 RF-8 WGS 84 geografico se trata como ETRS89 y lo dice`() {
        for (row in rows.filter { it.getValue("estado") == "WGS84" }) {
            val crs = ShpCrsCatalog.resolve(text(row.getValue("archivo"))).shouldBeInstanceOf<GpkgCrs.Supported>()

            crs.epsg shouldBe 4326
            crs.wgs84 shouldBe true
            crs.unit shouldBe GpkgCrsUnit.DEGREE
        }
    }

    @Test
    fun `S030 RF-9 los sistemas que la app no ofrece no estan disponibles y llevan el nombre del prj`() {
        val unavailable = rows.filter { it.getValue("estado") == "NO_DISPONIBLE" }

        (unavailable.size >= 8 * 3) shouldBe true
        for (row in unavailable) {
            // Desde S057/RF-5 los que están en la biblioteca de SC se reconocen por el nombre del prj; los demás siguen sin estar disponibles.
            when (val crs = ShpCrsCatalog.resolve(text(row.getValue("archivo")))) {
                is GpkgCrs.Unavailable -> crs.name shouldBe row.getValue("nombre_en_el_prj")
                is GpkgCrs.Library -> Unit
                else -> error("${row.getValue("archivo")}: $crs")
            }
        }
        // ETRS89 UTM 28 (25828) es ETRS89 en el huso 28, que la app no ofrece aunque REGCAN95 sí lo tenga: es de la biblioteca (S057)
        ShpCrsCatalog.resolve(text("prj/esri-25828.prj")).shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 25828
        // ED50 geográficas (4230) y una proyección en pies de Estados Unidos (2263), también
        ShpCrsCatalog.resolve(text("prj/esri-4230.prj")).shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 4230
        ShpCrsCatalog.resolve(text("prj/esri-2263.prj")).shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 2263
    }

    @Test
    fun `S030 RF-9 un prj con la autoridad de un sistema que la app no tiene no esta disponible con su codigo`() {
        // ETRS89 UTM 33 está en la biblioteca (S057/RF-4): con autoridad se reconoce por su código y sin ella, por su nombre y sus parámetros
        ShpCrsCatalog.resolve(text("prj/gdal-25833.prj")).shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 25833
        ShpCrsCatalog.resolve(text("prj/esri-25833.prj")).shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 25833
        // con una autoridad que ni la app ni la biblioteca tienen, sigue sin estar disponible con su código
        val crs = ShpCrsCatalog.resolve(
            text("prj/gdal-25833.prj").replace("\"25833\"", "\"999999\""),
        ).shouldBeInstanceOf<GpkgCrs.Unavailable>()
        crs.authority shouldBe "EPSG"
        crs.code shouldBe 999999
    }

    @Test
    fun `S030 RF-9 sin prj o con un prj que no es un WKT el sistema no esta declarado`() {
        ShpCrsCatalog.resolve(null) shouldBe GpkgCrs.Undeclared
        ShpCrsCatalog.resolve("") shouldBe GpkgCrs.Undeclared
        ShpCrsCatalog.resolve(text("sistemas/prj_ilegible.prj")) shouldBe GpkgCrs.Undeclared
    }

    @Test
    fun `S030 RF-8 un sistema se reconoce por sus parametros y no por su nombre`() {
        val renamed = text("prj/esri-25830.prj").replace("ETRS_1989_UTM_Zone_30N", "Mi sistema de obra")

        ShpCrsCatalog.resolve(renamed).shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe 25830
    }

    @Test
    fun `S030 RF-8 un cambio en cualquier parametro de la proyeccion lo deja no disponible`() {
        val base = text("prj/esri-25830.prj")
        val variants = mapOf(
            "escala" to base.replace("0.9996", "0.9999"),
            "falso este" to base.replace("PARAMETER[\"False_Easting\",500000.0]", "PARAMETER[\"False_Easting\",600000.0]"),
            "falso norte" to base.replace("PARAMETER[\"False_Northing\",0.0]", "PARAMETER[\"False_Northing\",10000000.0]"),
            "meridiano no es de un huso" to base.replace("PARAMETER[\"Central_Meridian\",-3.0]", "PARAMETER[\"Central_Meridian\",-4.0]"),
            "latitud de origen" to base.replace("PARAMETER[\"Latitude_Of_Origin\",0.0]", "PARAMETER[\"Latitude_Of_Origin\",40.0]"),
            "unidad en pies" to base.replace("UNIT[\"Meter\",1.0]", "UNIT[\"Foot\",0.3048]"),
            "otra proyeccion" to base.replace("Transverse_Mercator", "Lambert_Conformal_Conic"),
            "elipsoide de otro datum" to
                base.replace("SPHEROID[\"GRS_1980\",6378137.0,298.257222101]", "SPHEROID[\"Clarke_1866\",6378206.4,294.9786982]"),
            "sin parametros" to base.replace(Regex("PARAMETER\\[[^\\]]*\\],?"), ""),
        )
        for ((name, wkt) in variants) {
            withClue(name) { (ShpCrsCatalog.resolve(wkt) is GpkgCrs.Unavailable) shouldBe true }
        }
        // Con el nombre del datum desconocido la estructura de España no lo reconoce, pero la biblioteca sí: el nombre del sistema, los parámetros
        // y el elipsoide son los de ETRS89 UTM 30 (S057/RF-5).
        val unknownDatum = base.replace("D_ETRS_1989", "D_Desconocido").replace("GCS_ETRS_1989", "GCS_Desconocido")
        ShpCrsCatalog.resolve(unknownDatum).shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 25830
    }

    @Test
    fun `S030 RF-8 los nombres de datum de QGIS y de ArcGIS dan la misma familia`() {
        // QGIS escribe «D_Red_Geodesica_de_Canarias_1995» y «GCS_REGCAN95»: la familia sale de cualquiera de los dos
        val canarias = File(ShpFixtures.folder, "qgis/canarias-4083/vertices.prj").readText()

        ShpCrsCatalog.resolve(canarias).shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe 4083
        ShpCrsCatalog.resolve(canarias.replace("GCS_REGCAN95", "GCS_Otro")).shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe 4083
        ShpCrsCatalog.resolve(
            canarias.replace("D_Red_Geodesica_de_Canarias_1995", "D_Otro"),
        ).shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe
            4083
        // sin ninguno de los dos nombres de datum la estructura no basta, pero la biblioteca lo reconoce por el nombre y los parámetros (S057/RF-5)
        ShpCrsCatalog.resolve(canarias.replace("D_Red_Geodesica_de_Canarias_1995", "D_Otro").replace("GCS_REGCAN95", "GCS_Otro"))
            .shouldBeInstanceOf<GpkgCrs.Library>().epsg shouldBe 4083
    }

    @Test
    fun `S030 RF-8 los prj de las capas de ejemplo dan el sistema esperado`() {
        val capas = ShpFixtures.expected("esperado-capas.csv")
        var checked = 0
        for (row in capas) {
            val base = "${row.getValue("escenario")}/${row.getValue("capa")}.prj"
            val prj = ShpFixtures.file(base).takeIf { it.exists() }?.readText()
            val expected = row.getValue("sistema_app")

            val crs = ShpCrsCatalog.resolve(prj)

            when (expected) {
                "SIN_SISTEMA" -> crs shouldBe GpkgCrs.Undeclared
                // S057: los de la biblioteca se reconocen
                "NO_DISPONIBLE" -> (crs is GpkgCrs.Unavailable || crs is GpkgCrs.Library) shouldBe true
                else -> ProjectCrs.valueOf(expected.replace("GEOGRAFICAS", "GEOGRAPHIC").replace('-', '_')).epsg shouldBe
                    crs.shouldBeInstanceOf<GpkgCrs.Supported>().epsg.let { if (it == 4326) 4258 else it }
            }
            checked++
        }
        (checked > 40) shouldBe true
    }

    @Test
    fun `S030 RF-8 los prj que escribe QGIS dan el sistema que QGIS reconoce`() {
        for (row in ShpFixtures.expected("qgis/esperado-qgis-capas.csv")) {
            val prj = ShpFixtures.file("qgis/${row.getValue("escenario")}/${row.getValue("capa")}.prj").readText()

            ShpCrsCatalog.resolve(prj).shouldBeInstanceOf<GpkgCrs.Supported>().epsg shouldBe
                row.getValue("sistema_qgis").removePrefix("EPSG:").toInt()
        }
    }
}

package es.topophone.core.formats.crs

import es.topophone.core.formats.gpkg.GpkgCrs
import es.topophone.core.formats.gpkg.GpkgCrsCatalog
import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.formats.raster.RasterCrsResolver
import es.topophone.core.formats.shp.ShpCrsCatalog
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Reconocer en la biblioteca el SC de un Shapefile, un GeoPackage o un GeoTIFF, por su código o por su nombre (S057/RF-4, RF-5, RF-6). */
class CrsRecognitionTest {
    private val lambert93Esri =
        """PROJCS["RGF_1993_Lambert_93",GEOGCS["GCS_RGF_1993",DATUM["D_RGF_1993",SPHEROID["GRS_1980",6378137.0,298.257222101]],""" +
            """PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Lambert_Conformal_Conic"],""" +
            """PARAMETER["False_Easting",700000.0],""" +
            """PARAMETER["False_Northing",6600000.0],PARAMETER["Central_Meridian",3.0],PARAMETER["Standard_Parallel_1",49.0],""" +
            """PARAMETER["Standard_Parallel_2",44.0],PARAMETER["Latitude_Of_Origin",46.5],UNIT["Meter",1.0]]"""

    @Test
    fun `S057 RF-4 un EPSG de la biblioteca que no es de Espana se reconoce`() {
        LibraryCrsRecognition.byEpsg(2154) shouldBe RecognizedCrs(2154, "RGF93 v1 / Lambert-93")
        LibraryCrsRecognition.byEpsg(999999).shouldBeNull()
    }

    @Test
    fun `S057 RF-4 un GeoPackage con el EPSG de Lambert 93 da un SC de la biblioteca y los de Espana siguen igual`() {
        GpkgCrsCatalog.resolve(2154, "EPSG", 2154) shouldBe GpkgCrs.Library(2154, "RGF93 v1 / Lambert-93")
        GpkgCrsCatalog.resolve(25830, "EPSG", 25830).shouldBeInstanceOf<GpkgCrs.Supported>()
        GpkgCrsCatalog.resolve(4326, "EPSG", 4326).shouldBeInstanceOf<GpkgCrs.Supported>()
    }

    @Test
    fun `S057 RF-6 un EPSG que no esta en la biblioteca sigue sin estar disponible`() {
        GpkgCrsCatalog.resolve(1, "EPSG", 999999) shouldBe GpkgCrs.Unavailable("EPSG", 999999)
        GpkgCrsCatalog.resolve(1, "ESRI", 102100) shouldBe GpkgCrs.Unavailable("ESRI", 102100)
    }

    @Test
    fun `S057 RF-4 un GeoTIFF con un EPSG de la biblioteca da un SC de la biblioteca`() {
        RasterCrsResolver.resolve(2154) shouldBe RasterCrsDeclaration.Library(2154, "RGF93 v1 / Lambert-93")
        RasterCrsResolver.resolve(25830).shouldBeInstanceOf<RasterCrsDeclaration.Supported>()
        RasterCrsResolver.resolve(999999) shouldBe RasterCrsDeclaration.Unavailable(999999)
    }

    @Test
    fun `S057 RF-5 un prj con AUTHORITY de EPSG se reconoce por el codigo`() {
        val prj = """PROJCS["lo que sea",GEOGCS["x"],PROJECTION["Lambert_Conformal_Conic_2SP"],AUTHORITY["EPSG","2154"]]"""

        ShpCrsCatalog.resolve(prj) shouldBe GpkgCrs.Library(2154, "RGF93 v1 / Lambert-93")
    }

    @Test
    fun `S057 RF-5 un prj de ESRI sin codigo se reconoce por su nombre`() {
        ShpCrsCatalog.resolve(lambert93Esri) shouldBe GpkgCrs.Library(2154, "RGF93 v1 / Lambert-93")
    }

    @Test
    fun `S057 RF-5 el nombre se normaliza sin signos, espacios ni mayusculas y vale el de EPSG o el de ESRI`() {
        LibraryCrsRecognition.byName("ETRS_1989_UTM_Zone_32N").shouldBeInstanceOf<NameMatch.Single>().crs.epsg shouldBe 25832
        LibraryCrsRecognition.byName("etrs89 / utm zone 32n").shouldBeInstanceOf<NameMatch.Single>().crs.epsg shouldBe 25832
        LibraryCrsRecognition.normalized("ETRS_1989 UTM-Zone/32N") shouldBe "etrs1989utmzone32n"
    }

    @Test
    fun `S057 RF-6 un nombre que corresponde a varios SC los da todos para elegir`() {
        val duplicated = es.topophone.core.geodesy.catalog.CrsCatalog.bundled.crs.groupBy { LibraryCrsRecognition.normalized(it.name) }
            .filterValues { it.size > 1 }.keys.first()

        val match = LibraryCrsRecognition.byName(duplicated).shouldBeInstanceOf<NameMatch.Several>()

        match.candidates shouldHaveAtLeastSize 2
        match.candidates.map { it.epsg } shouldBe match.candidates.map { it.epsg }.sorted()
    }

    @Test
    fun `S057 RF-6 un prj con un nombre ambiguo o desconocido pide elegir`() {
        val unknown = """PROJCS["Sistema inventado de la obra",GEOGCS["x"],PROJECTION["Transverse_Mercator"]]"""

        val crs = ShpCrsCatalog.resolve(unknown).shouldBeInstanceOf<GpkgCrs.Unavailable>()

        crs.name shouldBe "Sistema inventado de la obra"
        crs.candidates shouldBe emptyList()
    }

    @Test
    fun `S057 RF-27 los prj de Espana se resuelven como antes, antes de buscar por nombre`() {
        val utm30 =
            """PROJCS["ETRS_1989_UTM_Zone_30N",GEOGCS["GCS_ETRS_1989",DATUM["D_ETRS_1989",""" +
                """SPHEROID["GRS_1980",6378137.0,298.257222101]],""" +
                """PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"],""" +
                """PARAMETER["False_Easting",500000.0],""" +
                """PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",-3.0],PARAMETER["Scale_Factor",0.9996],""" +
                """PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]"""

        ShpCrsCatalog.resolve(utm30).shouldBeInstanceOf<GpkgCrs.Supported>()
    }

    @Test
    fun `S057 RF-5 los nombres de ESRI de la biblioteca entran en el indice`() {
        LibraryCrsRecognition.byName("RGF_1993_Lambert_93").shouldBeInstanceOf<NameMatch.Single>().crs.epsg shouldBe 2154
        LibraryCrsRecognition.byName("ETRS_1989_UTM_Zone_30N").shouldBeInstanceOf<NameMatch.Single>().crs.epsg shouldBe 25830
    }

    @Test
    fun `S057 RF-6 un nombre vacio o sin ninguna letra no reconoce nada`() {
        LibraryCrsRecognition.byName("") shouldBe NameMatch.None
        LibraryCrsRecognition.byName("___ --") shouldBe NameMatch.None
    }
}

package es.topophone.core.formats.raster

import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Reconocer un ráster por su contenido y el sistema que declara (S045/RF-2, RF-3, RF-4, RF-6, RF-8). */
class RasterReaderTest {

    @Test
    fun `S045 RF-2 el formato sale del contenido y no del nombre`() {
        RasterReader.detect(RasterFixtures.source("f32-lzw-teselas-area.tif")) shouldBe RasterFormat.GEOTIFF
        RasterReader.detect(RasterFixtures.source("esquina.asc")) shouldBe RasterFormat.ASCII_GRID
        // Un ASCII Grid con nombre de TIFF y un TIFF con nombre de texto: la extensión no cuenta.
        RasterReader.detect(ByteArrayRasterSource(RasterFixtures.bytes("centro.asc"))) shouldBe RasterFormat.ASCII_GRID
        RasterReader.detect(ByteArrayRasterSource(RasterFixtures.bytes("i16-sin-tiras.tif"))) shouldBe RasterFormat.GEOTIFF
        RasterReader.detect(RasterFixtures.source("no-es-raster.txt")) shouldBe null
        RasterReader.detect(RasterFixtures.source("vacio.tif")) shouldBe null
        RasterReader.detect(RasterFixtures.source("bigtiff.tif")) shouldBe RasterFormat.GEOTIFF
    }

    @Test
    fun `S045 RF-3 cada fichero de ficheros csv se escanea con el resultado esperado`() {
        for (row in RasterFixtures.files) {
            val name = row.getValue("fichero")
            val result = RasterReader.scan(RasterFixtures.source(name))
            when (val expected = row.getValue("resultado")) {
                "OK", "NO_DATA_CELLS" -> {
                    val header = (result as RasterScan.Found).header
                    header.columns shouldBe if (expected == "OK") row.getValue("columnas").toInt() else 8
                }
                else -> result shouldBe RasterScan.Rejected(RasterRejection.valueOf(expected))
            }
        }
    }

    @Test
    fun `S045 RF-6 un GeoTIFF multibanda dice cuantas bandas tiene`() {
        (RasterReader.scan(RasterFixtures.source("f32-multibanda.tif")) as RasterScan.Found).header.bandCount shouldBe 2
        (RasterReader.scan(RasterFixtures.source("f32-sin-tiras-area.tif")) as RasterScan.Found).header.bandCount shouldBe 1
    }

    @Test
    fun `S045 RF-2 leer por bandas elige el lector del formato`() {
        for (name in listOf("f32-deflate-pred3-teselas.tif", "esquina.asc")) {
            var rows = 0
            val stats = RasterReader.readBands(RasterFixtures.source(name), { _, count, _ -> rows += count })

            rows shouldBe 36
            stats.dataCells shouldBe 1436
        }
    }

    @Test
    fun `S045 RF-8 el sistema que declara el fichero es uno de los de la app o no`() {
        val etrs30 = RasterCrsResolver.resolve(25830) as RasterCrsDeclaration.Supported
        etrs30.source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30)
        etrs30.wgs84 shouldBe false
        (RasterCrsResolver.resolve(25829) as RasterCrsDeclaration.Supported).source shouldBe CadSourceCrs.Utm(GeodeticDatum.ETRS89, 29)
        (RasterCrsResolver.resolve(4258) as RasterCrsDeclaration.Supported).source shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
        (RasterCrsResolver.resolve(4082) as RasterCrsDeclaration.Supported).source shouldBe CadSourceCrs.Utm(GeodeticDatum.REGCAN95, 27)
        (RasterCrsResolver.resolve(23030) as RasterCrsDeclaration.Supported).source shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        RasterCrsResolver.resolve(4326).let {
            (it as RasterCrsDeclaration.Supported).source shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
            it.wgs84 shouldBe true
        }
    }

    @Test
    fun `S045 RF-8 un sistema que la app no ofrece o sin declarar espera a que el usuario elija`() {
        RasterCrsResolver.resolve(3857) shouldBe RasterCrsDeclaration.Unavailable(3857)
        RasterCrsResolver.resolve(null) shouldBe RasterCrsDeclaration.Undeclared
        RasterCrsResolver.choices.size shouldBe 10
    }

    @Test
    fun `S045 RF-8 el sistema declarado por cada fichero de prueba se reconoce`() {
        for (row in RasterFixtures.readable.filter { it["formato"] == "geotiff" }) {
            val header = (RasterReader.scan(RasterFixtures.source(row.getValue("fichero"))) as RasterScan.Found).header

            RasterCrsResolver.resolve(header.epsg) shouldBe RasterCrsResolver.resolve(row.getValue("epsg").toInt())
            (RasterCrsResolver.resolve(header.epsg) is RasterCrsDeclaration.Supported) shouldBe true
        }
    }
}

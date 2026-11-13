package es.topophone.core.formats.wms

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import org.junit.jupiter.api.Test

/** Con respuestas reales de los servicios grabadas en `fixtures/formatos/wms/info/` el 13/11/2026 y unas pocas sintéticas. */
class FeatureInfoTextTest {

    private fun recorded(name: String) = FeatureInfoText.parse(File(WmsFixtures.folder, "info/$name").readBytes())

    private fun parse(text: String) = FeatureInfoText.parse(text.toByteArray(Charsets.UTF_8))

    private fun text(result: FeatureInfoResult): String = result.shouldBeInstanceOf<FeatureInfoResult.Text>().text

    @Test
    fun `S036 RF-36 el texto de GeoServer del IGN sale con su capa y sus pares sin la cabecera ni el numero de entidad`() {
        text(recorded("ign-pnoa-ma.txt")) shouldBe "OI.MosaicElement\nFECHA: 2024-07\nRESOLUCION: 0.25"
    }

    @Test
    fun `S036 RF-36 el MDT sale con el nombre del tipo sin la direccion interna del servidor`() {
        text(recorded("ign-mdt.txt")) shouldBe
            "MDT02_H31\nGRAY_INDEX: 40.19172668457031\n\nMDT02_H30\nGRAY_INDEX: 40.17505645751953"
    }

    @Test
    fun `S036 RF-36 los limites administrativos salen sin la geometria`() {
        val result = text(recorded("ign-limites-admin.txt"))

        result.contains("GEOMETRY") shouldBe false
        result.contains("192.168.") shouldBe false
        result.lines().any { it.contains(": ") } shouldBe true
    }

    @Test
    fun `S036 RF-36 la ficha del ICV conserva sus pares y la del ICGC su acento`() {
        val icv = text(recorded("icv-orto-2024.txt")).lines()
        val icgc = text(recorded("icgc-orto-territorial.txt"))

        icv.take(4) shouldBe listOf("distribucion_descargas", "id: 1408", "hoja: 0641_4-1", "escala_pix: 0025")
        icv.none { it.startsWith("Feature") } shouldBe true
        icgc shouldBe
            "ortofoto_color_vigent\ncolor: RGB\ngsd: 0.25\ndata_inicial: 2025/06/18 00:00:00\ndata_final: 2025/06/28 00:00:00\norigen: Píxel Font"
    }

    @Test
    fun `S036 RF-36 una respuesta con un solo valor de pixel sale tal cual`() {
        text(recorded("euskadi-orto-pixel.txt")) shouldBe "@ORTO_EGUNERATUENA_MAS_ACTUALIZADA RGB.Red;RGB.Green;RGB.Blue; 134;129;134;"
    }

    @Test
    fun `S036 RF-36 los atributos null o vacios y las geometrias se quitan de la ficha de Madrid y de Navarra`() {
        val madrid = text(recorded("madrid-bta.txt"))
        val navarra = text(recorded("navarra-idena.txt"))

        madrid.lines().first() shouldBe "IDEM_BTA_CUBIERTER_POL_17"
        madrid.contains("null") shouldBe false
        madrid.contains("idioma_des: Español o castellano") shouldBe true
        madrid.contains("BTA:") shouldBe false
        navarra.contains("CARBTA_REFERE_Pol_Navarra_MF_BTA") shouldBe true
        navarra.contains("FEATURE: 7200001") shouldBe true
        navarra.contains("GEOMETRY") shouldBe false
        navarra.contains("the_geom") shouldBe false
        navarra.contains("https://idena") shouldBe false
    }

    @Test
    fun `S036 RF-38 no features were found, Search returned no results y las entidades vacias son sin datos`() {
        recorded("ign-hidrografia-sin-datos.txt") shouldBe FeatureInfoResult.NoData
        recorded("andalucia-mta10v-sin-resultados.txt") shouldBe FeatureInfoResult.NoData
        recorded("grafcan-orto-territorial-vacio.txt") shouldBe FeatureInfoResult.NoData
    }

    @Test
    fun `S036 RF-38 un cuerpo vacio o solo con espacios es sin datos`() {
        parse("") shouldBe FeatureInfoResult.NoData
        parse("  \r\n \t ") shouldBe FeatureInfoResult.NoData
        parse("GetFeatureInfo results:\n\n") shouldBe FeatureInfoResult.NoData
    }

    @Test
    fun `S036 RF-38 una ficha de HTML que solo trae su cabecera sin valor es sin datos`() {
        recorded("catastro-wms-sin-valor.html") shouldBe FeatureInfoResult.NoData
    }

    @Test
    fun `S036 RF-36 la parcela del Catastro en XHTML sale como pares de sus filas con las entidades resueltas`() {
        val result = text(recorded("catastro-inspire-parcela-1.1.1.html")).lines()

        result.first() shouldBe "Get Feature Info Response"
        result.contains("Requested Layer: CadastralParcel") shouldBe true
        result.contains("AreaValue: 43375 m2") shouldBe true
        result.contains("nationalCadastralReference: 1249004VK4714G") shouldBe true
        result.any { it.contains("<") || it.contains("font-family") } shouldBe false
    }

    @Test
    fun `S036 RF-39 una excepcion de servicio con parcela no encontrada es sin datos y otra es un error con su mensaje`() {
        recorded("catastro-inspire-excepcion.xml") shouldBe FeatureInfoResult.NoData
        val other = "<?xml version=\"1.0\"?><ServiceExceptionReport version=\"1.1.1\"><ServiceException code=\"LayerNotQueryable\">" +
            "La capa no se puede consultar</ServiceException></ServiceExceptionReport>"

        parse(other) shouldBe FeatureInfoResult.ServiceError("La capa no se puede consultar")
    }

    @Test
    fun `S036 RF-36 el GML sale con el nombre de cada entidad y sus elementos hoja sin geometrias ni vacios`() {
        val gml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<wfs:FeatureCollection xmlns:wfs=\"http://www.opengis.net/wfs\" xmlns:gml=\"http://www.opengis.net/gml\" xmlns:ms=\"x\">" +
            "<gml:boundedBy><gml:Box srsName=\"EPSG:3857\"><gml:coordinates>1,2 3,4</gml:coordinates></gml:Box></gml:boundedBy>" +
            "<gml:featureMember><ms:parcelas fid=\"parcelas.1\"><ms:referencia>1249004VK4714G</ms:referencia><ms:area>43375</ms:area>" +
            "<ms:nota></ms:nota><ms:the_geom><gml:Polygon><gml:outerBoundaryIs><gml:LinearRing><gml:coordinates>1,2 3,4 5,6 1,2" +
            "</gml:coordinates></gml:LinearRing></gml:outerBoundaryIs></gml:Polygon></ms:the_geom></ms:parcelas></gml:featureMember>" +
            "<gml:featureMember><ms:parcelas fid=\"parcelas.2\"><ms:referencia>2</ms:referencia></ms:parcelas></gml:featureMember>" +
            "</wfs:FeatureCollection>"

        text(parse(gml)) shouldBe "parcelas\nreferencia: 1249004VK4714G\narea: 43375\n\nparcelas\nreferencia: 2"
    }

    @Test
    fun `S036 RF-36 un XML sin entidades de GML sale con sus elementos hoja`() {
        val xml = "<GetFeatureInfoResponse><Layer name=\"x\"><Feature><nombre>A &amp; B</nombre><vacio/></Feature></Layer>" +
            "</GetFeatureInfoResponse>"

        text(parse(xml)) shouldBe "nombre: A & B"
    }

    @Test
    fun `S036 RF-36 el texto en ISO-8859-1 y el que lleva BOM de UTF-8 se leen bien`() {
        val latin = "Ñandú = 'sí'".toByteArray(Charsets.ISO_8859_1)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a = 'ñ'".toByteArray()

        FeatureInfoText.parse(latin) shouldBe FeatureInfoResult.Text("Ñandú: sí")
        FeatureInfoText.parse(bom) shouldBe FeatureInfoResult.Text("a: ñ")
    }

    @Test
    fun `S036 RF-36 los saltos de linea de Windows y los separadores no dejan huecos`() {
        text(parse("Layer 'capa'\r\n----\r\n  Feature 0:\r\n    a = '1'\r\n    b = ''\r\n    c = 'null'\r\n")) shouldBe "capa\na: 1"
    }

    @Test
    fun `S036 RF-36 una respuesta enorme se recorta con puntos suspensivos`() {
        val result = text(parse("clave = '" + "x".repeat(30_000) + "'"))

        result.length shouldBe 20_001
        result.endsWith("…") shouldBe true
    }

    @Test
    fun `S036 RF-36 el HTML con entidades numericas y con nombre se decodifica y un bloque style no deja texto`() {
        val html = "<html><head><title>t</title><style>td { color: red; }</style></head><body><table>" +
            "<tr><td>Nombre</td><td>Pe&ntilde;a &#241; &#xF1; &amp;</td></tr>" +
            "<tr><td>Calle</td><td>Mayor<br>12</td></tr></table></body></html>"

        text(parse(html)) shouldBe "Nombre: Peña ñ ñ &\nCalle: Mayor\n12"
    }

    @Test
    fun `S036 RF-36 un XML mal formado se limpia como HTML en lugar de fallar`() {
        parse("<a>un valor</a><b>sin cerrar") shouldBe FeatureInfoResult.Text("un valorsin cerrar")
    }
}

package es.topophone.core.formats.wms

import es.topophone.core.model.wms.WmsRequestMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class WmsPresetsTest {

    private val all = WmsPresets.all

    private fun json(vararg fields: String) = "[{" + fields.joinToString(",") + "}]"

    private val valid = arrayOf(
        "\"key\":\"x\"",
        "\"name\":\"X\"",
        "\"region\":\"España\"",
        "\"group\":\"G\"",
        "\"address\":\"https://x.es/wms\"",
        "\"attribution\":\"© X\"",
        "\"license\":\"Libre\"",
        "\"requestMode\":\"TILES\"",
        "\"suggestedLayers\":[\"a\"]",
    )

    private fun without(field: String) = json(*valid.filterNot { it.startsWith("\"$field\"") }.toTypedArray())

    private fun with(field: String, value: String): String {
        val others = valid.filterNot { it.startsWith("\"$field\"") }
        return json(*(others + "\"$field\":$value").toTypedArray())
    }

    @Test
    fun `S036 RF-13 el catalogo trae los servicios del IGN, del Catastro y de las comunidades pedidas`() {
        val groups = all.map { it.group }.toSet()

        groups shouldBe setOf(
            "Catastro",
            "IGN",
            "ICV ortofotos",
            "ICV cartografía",
            "ICGC",
            "GRAFCAN",
            "Andalucía",
            "Navarra",
            "País Vasco",
            "Madrid",
            "Galicia",
        )
        (all.size >= 55) shouldBe true
    }

    @Test
    fun `S036 RF-13 el IGN tiene el PNOA, la cartografia base, el MDT, los limites y la hidrografia`() {
        all.filter { it.group == "IGN" }.map { it.key }.sorted() shouldBe
            listOf("ign-base", "ign-hidrografia", "ign-limites-admin", "ign-mdt", "ign-pnoa-ma")
        WmsPresets.byKey("ign-pnoa-ma")?.suggestedLayers shouldBe listOf("OI.OrthoimageCoverage")
        WmsPresets.byKey("ign-pnoa-ma")?.address shouldBe "https://www.ign.es/wms-inspire/pnoa-ma"
    }

    @Test
    fun `S036 RF-13 las ortofotos del ICV van de 1945 a 2025 y cada una lleva su ano`() {
        val orto = all.filter { it.group == "ICV ortofotos" }

        (orto.size >= 35) shouldBe true
        orto.all { it.year != null } shouldBe true
        orto.mapNotNull { it.year }.min() shouldBe 1945
        orto.mapNotNull { it.year }.max() shouldBe 2025
        orto.first().year shouldBe 2025 // del vuelo más reciente al más antiguo
        WmsPresets.byKey("icv-orto-2024")?.address shouldBe "https://terramapas.icv.gva.es/0202_2024CVAL0025"
        WmsPresets.byKey("icv-orto-2024")?.suggestedLayers shouldBe listOf("2024CVAL0025_RGB")
    }

    @Test
    fun `S036 RF-13 la cartografia del ICV tiene sus cinco escalas`() {
        all.filter { it.group == "ICV cartografía" }.map { it.key }.sorted() shouldBe
            listOf("icv-bcv05", "icv-bcv100", "icv-bcv200", "icv-bcv25", "icv-bcv50")
    }

    @Test
    fun `S036 RF-13 Navarra es un solo servicio con la ortofoto y el mapa base`() {
        val navarra = all.filter { it.group == "Navarra" }

        navarra.size shouldBe 1
        navarra.single().suggestedLayers shouldBe listOf("ortofoto_maxima_actualidad", "mapaBase")
    }

    @Test
    fun `S036 RF-13 Pais Vasco y Galicia entran solo con su ortofoto`() {
        all.filter { it.group == "País Vasco" }.map { it.key } shouldBe listOf("euskadi-orto")
        all.filter { it.group == "Galicia" }.map { it.key } shouldBe listOf("galicia-orto-pnoa-2023")
    }

    @Test
    fun `S036 RF-13 cada preset tiene atribucion, licencia, region y capas sugeridas`() {
        for (preset in all) {
            preset.attribution.isNotBlank() shouldBe true
            preset.license.isNotBlank() shouldBe true
            preset.region.isNotBlank() shouldBe true
            preset.suggestedLayers.isNotEmpty() shouldBe true
        }
    }

    @Test
    fun `S036 RNF-T4 todas las direcciones son HTTPS y no se repite ni la clave ni la direccion`() {
        all.all { it.address.startsWith("https://") } shouldBe true
        all.map { it.key }.toSet().size shouldBe all.size
        all.map { it.address }.toSet().size shouldBe all.size
    }

    @Test
    fun `S036 RF-16 el Catastro y GRAFCAN piden una imagen y los demas teselas`() {
        val image = all.filter { it.requestMode == WmsRequestMode.IMAGE }.map { it.key }.sorted()

        image shouldBe listOf("catastro-inspire", "catastro-wms", "grafcan-mti", "grafcan-orto-territorial", "grafcan-orto-urbana")
        all.filter { it.requestMode == WmsRequestMode.TILES }.size shouldBe all.size - 5
    }

    @Test
    fun `S036 RF-36 el Catastro INSPIRE se consulta en 1_1_1 y ningun otro fuerza la version`() {
        WmsPresets.byKey("catastro-inspire")?.infoVersion shouldBe "1.1.1"
        all.filter { it.infoVersion != null }.map { it.key } shouldBe listOf("catastro-inspire")
    }

    @Test
    fun `S036 RF-29 la ortofoto va en JPEG y GRAFCAN respeta los formatos que ofrece`() {
        WmsPresets.byKey("ign-pnoa-ma")?.preferredFormat shouldBe "image/jpeg"
        WmsPresets.byKey("icv-orto-2024")?.preferredFormat shouldBe "image/jpeg"
        WmsPresets.byKey("grafcan-orto-urbana")?.preferredFormat shouldBe "image/jpeg"
        WmsPresets.byKey("grafcan-mti")?.preferredFormat shouldBe "image/png"
        WmsPresets.byKey("ign-base")?.preferredFormat shouldBe "image/png"
    }

    @Test
    fun `S036 RF-13 la licencia de GRAFCAN dice que no se puede usar con fines comerciales`() {
        WmsPresets.byKey("grafcan-orto-urbana")?.license.orEmpty() shouldContain "comercial"
    }

    @Test
    fun `S036 RF-13 una clave desconocida no da preset`() {
        WmsPresets.byKey("no-existe") shouldBe null
    }

    @Test
    fun `S036 RF-13 un preset sin alguno de sus campos obligatorios se rechaza con el nombre del campo`() {
        listOf("key", "name", "region", "group", "address", "attribution", "license", "requestMode").forEach { field ->
            shouldThrow<IllegalArgumentException> { WmsPresets.parse(without(field)) }.message.orEmpty() shouldContain field
        }
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(without("suggestedLayers")) }.message.orEmpty() shouldContain "capas"
    }

    @Test
    fun `S036 RF-13 un valor que no vale se rechaza`() {
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(with("key", "\"Con Mayusculas\"")) }
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(with("address", "\"http://x.es/wms\"")) }
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(with("address", "\"https://x.es/wms?map=a\"")) }
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(with("requestMode", "\"OTRO\"")) }
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(with("preferredFormat", "\"image/gif\"")) }
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(with("infoVersion", "\"1.0.0\"")) }
        shouldThrow<IllegalArgumentException> { WmsPresets.parse(with("licenseUrl", "\"http://x.es/licencia\"")) }
    }

    @Test
    fun `S036 RF-13 una clave o una direccion repetidas se rechazan`() {
        val one = "{" + valid.joinToString(",") + "}"
        val sameAddress = "{" + (valid.filterNot { it.startsWith("\"key\"") } + "\"key\":\"y\"").joinToString(",") + "}"

        shouldThrow<IllegalArgumentException> { WmsPresets.parse("[$one,$one]") }.message.orEmpty() shouldContain "claves"
        shouldThrow<IllegalArgumentException> { WmsPresets.parse("[$one,$sameAddress]") }.message.orEmpty() shouldContain "direcciones"
    }

    @Test
    fun `S036 RF-13 un preset minimo valido se lee con sus opcionales vacios`() {
        val preset = WmsPresets.parse(json(*valid)).single()

        preset.year shouldBe null
        preset.preferredFormat shouldBe null
        preset.infoVersion shouldBe null
        preset.licenseUrl shouldBe null
        preset.notes shouldBe ""
    }
}

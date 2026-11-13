package es.topophone.core.formats.wms

import io.kotest.matchers.shouldBe
import java.io.File
import java.security.MessageDigest
import kotlin.time.measureTimedValue
import org.junit.jupiter.api.Test

/**
 * Los GetCapabilities reales de los presets, grabados el 13/11/2026 por `tools/formatos/wms_presets.py` en
 * `fixtures/formatos/wms/presets/`,
 * se leen con `WmsCapabilitiesReader` y dan lo mismo que el lector de referencia de Python (S036/RF-9, RF-13).
 */
class WmsPresetFixturesTest {
    private val folder = File(WmsFixtures.folder, "presets")

    private fun documents(): List<File> = folder.listFiles { f -> f.isDirectory }.orEmpty()
        .flatMap { dir -> dir.listFiles { f -> f.name.startsWith("capabilities-") }.orEmpty().toList() }
        .sortedBy { name(it) }

    private fun name(doc: File) = "${doc.parentFile.name}/${doc.name}"

    private fun read(doc: File) = WmsCapabilitiesReader.read(doc.inputStream())

    private fun escaped(text: String) = text.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

    /** Las líneas de capa como las escribe `generar_wms.py`, para calcular el mismo resumen que `esperado-presets.txt`. */
    private fun layerLines(doc: String, caps: WmsCapabilities) = caps.layers.map { l ->
        "layer|$doc|${l.ordinal}|${l.depth}|${escaped(l.name.orEmpty())}|${escaped(l.title)}|${escaped(l.abstract)}|" +
            "${if (l.transparent) 1 else 0}|${if (l.queryable) 1 else 0}|${l.mercatorCode.orEmpty()}"
    }

    private fun sha256(text: String) =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    @Test
    fun `S036 RF-13 hay documentos grabados de los presets que no son ortofotos del ICV y de cuatro vuelos de ellas`() {
        val keys = documents().map { it.parentFile.name }.toSet()

        keys.all { WmsPresets.byKey(it) != null } shouldBe true
        keys.containsAll(WmsPresets.all.filter { it.group != "ICV ortofotos" }.map { it.key }) shouldBe true
        val icv = keys.filter { it.startsWith("icv-orto-") }.toSet()
        icv shouldBe setOf("icv-orto-2025-valencia", "icv-orto-2024", "icv-orto-2017", "icv-orto-1945-amsa")
    }

    @Test
    fun `S036 RF-9 todos los documentos se leen y tienen alguna capa que se puede anadir`() {
        val docs = documents()

        (docs.size >= 50) shouldBe true
        for (doc in docs) {
            val caps = read(doc)
            "${name(doc)}: ${caps.hasAddableLayer}" shouldBe "${name(doc)}: true"
        }
    }

    @Test
    fun `S036 RF-13 las capas sugeridas de cada preset estan en su documento con nombre y con Web Mercator`() {
        for (doc in documents()) {
            val preset = WmsPresets.byKey(doc.parentFile.name)!!
            val addable = read(doc).layers.filter { it.isAddable }.mapNotNull { it.name }.toSet()
            for (wanted in preset.suggestedLayers) "${name(doc)} $wanted: ${wanted in addable}" shouldBe "${name(doc)} $wanted: true"
        }
    }

    @Test
    fun `S036 RF-11 ningun documento pasa de los 10 niveles y los mas profundos que se leen llegan a 9`() {
        val depth = documents().maxOf { doc -> read(doc).layers.maxOf { it.depth } }

        (depth <= 9) shouldBe true
    }

    @Test
    fun `S036 RF-3 el lector de Kotlin da lo mismo que el de referencia de Python en todos los documentos reales`() {
        val expected = File(WmsFixtures.folder, "esperado-presets.txt").readLines().filter { it.isNotBlank() }
        val services = expected.filter { it.startsWith("service|") }.associateBy { it.split('|')[1] }
        val layers = expected.filter { it.startsWith("layers|") }.associateBy { it.split('|')[1] }
        val docs = documents()

        services.keys shouldBe docs.map { name(it) }.toSet()
        for (doc in docs) {
            val n = name(doc)
            val caps = read(doc)
            val service = services.getValue(n).split('|')
            val summary = layers.getValue(n).split('|')
            "$n version" to caps.version shouldBe ("$n version" to service[3])
            "$n title" to escaped(caps.title) shouldBe ("$n title" to service[4])
            "$n attribution" to escaped(caps.attribution) shouldBe ("$n attribution" to service[5])
            "$n map" to caps.mapFormats.joinToString(";") shouldBe ("$n map" to service[6])
            "$n info" to caps.infoFormats.joinToString(";") shouldBe ("$n info" to service[7])
            val lines = layerLines(n, caps)
            "$n layers" to lines.size.toString() shouldBe ("$n layers" to summary[2])
            "$n sha" to sha256(lines.joinToString("\n")) shouldBe ("$n sha" to summary[3])
        }
    }

    @Test
    fun `S036 RF-2 el Catastro contesta 1_1_1 a una peticion 1_3_0 y el INSPIRE 1_3_0 a una 1_1_1`() {
        read(File(folder, "catastro-wms/capabilities-1.3.0.xml")).version shouldBe "1.1.1"
        read(File(folder, "catastro-inspire/capabilities-1.1.1.xml")).version shouldBe "1.3.0"
        read(File(folder, "ign-limites-admin/capabilities-1.1.1.xml")).version shouldBe "1.3.0"
    }

    @Test
    fun `S036 RF-9 la ortofoto de Galicia solo anuncia EPSG 3785 y la del ICV EPSG 3857`() {
        val galicia = read(File(folder, "galicia-orto-pnoa-2023/capabilities-1.3.0.xml")).layers.single { it.name == "0" }
        val icv = read(File(folder, "icv-orto-2024/capabilities-1.3.0.xml")).layers.single { it.name == "2024CVAL0025_RGB" }

        galicia.mercatorCode shouldBe "EPSG:3785"
        icv.mercatorCode shouldBe "EPSG:3857"
    }

    @Test
    fun `S036 RF-11 el 1_1_1 de Andalucia con un prefijo sin declarar se lee`() {
        read(File(folder, "andalucia-mta10r-2013/capabilities-1.1.1.xml")).layers.any { it.name == "mta10r_2001-2013" } shouldBe true
    }

    @Test
    fun `S036 RF-11 el servicio de Navarra de 3 MB y mas de mil capas se lee en menos de 2 s`() {
        val doc = File(folder, "navarra-idena/capabilities-1.3.0.xml")

        val timed = measureTimedValue { read(doc) }

        println("S036 Navarra ${doc.length() / 1024} kB, ${timed.value.layers.size} capas: ${timed.duration.inWholeMilliseconds} ms")
        (doc.length() > 2_000_000) shouldBe true
        (timed.value.layers.size > 1_000) shouldBe true
        (timed.duration.inWholeMilliseconds < 2_000) shouldBe true
    }

    @Test
    fun `S036 RF-3 los nombres de capa con barras verticales y espacios del ICV se conservan`() {
        val names = read(File(folder, "icv-bcv05/capabilities-1.3.0.xml")).layers.mapNotNull { it.name }

        names.contains("OWS_0101_BCV05") shouldBe true
        names.any { it.contains(" | ") } shouldBe true
    }
}

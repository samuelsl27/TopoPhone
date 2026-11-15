package es.topophone.core.model.guide

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/**
 * Las cuatro listas de configuración: su contenido, que cada comprobación esté en un paso y que cada texto exista
 * (S043/RF-1, RF-2, RF-9).
 */
class ConfigListsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }
        ?: error("No se encuentra la raíz del repositorio")

    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([a-z_0-9]+)">(.*?)</string>""").findAll(File(root, path).readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    private val es = strings("app/src/main/res/values/strings.xml")
    private val en = strings("app/src/main/res/values-en/strings.xml")

    @Test
    fun `S043 RF-1 hay cuatro listas en este orden`() {
        ConfigLists.all.map { it.id } shouldBe
            listOf(ConfigListId.NTRIP_ROVER, ConfigListId.WIFI_ROVER, ConfigListId.TILT_ROVER, ConfigListId.BASE)
        ConfigListId.entries.forEach { ConfigLists.byId(it).id shouldBe it }
    }

    @Test
    fun `S043 RF-2 los ids de los pasos no se repiten ni dentro de una lista ni entre listas`() {
        val ids = ConfigLists.all.flatMap { list -> list.steps.map { it.id } }

        ids.toSet() shouldHaveSize ids.size
        ConfigLists.all.forEach { list -> list.steps.isNotEmpty() shouldBe true }
    }

    @Test
    fun `S043 RF-9 cada comprobacion esta como mucho en un paso de cada lista`() {
        for (list in ConfigLists.all) {
            val checks = list.steps.flatMap { it.checks }
            checks.toSet() shouldHaveSize checks.size
        }
    }

    @Test
    fun `S043 RF-9 el paso que resuelve una comprobacion sale de la lista y es null si la lista no la lleva`() {
        ConfigLists.stepFor(ConfigListId.NTRIP_ROVER, CheckId.GGA_RATE) shouldBe "ntrip_rate"
        ConfigLists.stepFor(ConfigListId.NTRIP_ROVER, CheckId.RTCM_BASE) shouldBe "ntrip_corrections"
        ConfigLists.stepFor(ConfigListId.WIFI_ROVER, CheckId.GGA_RATE) shouldBe "wifi_tcp"
        ConfigLists.stepFor(ConfigListId.TILT_ROVER, CheckId.TILT) shouldBe "tilt_etc"
        ConfigLists.stepFor(ConfigListId.BASE, CheckId.RTCM_OBSERVATIONS) shouldBe "base_observations"
        ConfigLists.stepFor(ConfigListId.WIFI_ROVER, CheckId.TILT).shouldBeNull()
    }

    @Test
    fun `S043 RF-4 las comprobaciones que da el comprobador estan todas en alguna lista`() {
        val used = ConfigLists.all.flatMap { list -> list.steps.flatMap { it.checks } }.toSet()

        CheckId.entries.filter { it !in used }.shouldBeEmpty()
    }

    @Test
    fun `S043 RF-4 un paso es comprobable solo si lleva comprobaciones y hay pasos de las dos clases`() {
        val steps = ConfigLists.all.flatMap { it.steps }

        steps.any { it.checkable } shouldBe true
        steps.any { !it.checkable } shouldBe true
        steps.filter { it.checkable }.all { it.checks.isNotEmpty() } shouldBe true
    }

    @Test
    fun `S043 RF-2 cada lista y cada paso tiene su titulo y su texto en espanol y en ingles`() {
        val keys = ConfigLists.all.flatMap { list ->
            listOf(ConfigLists.listTitleKey(list.id), ConfigLists.listSummaryKey(list.id)) +
                list.steps.flatMap { listOf(ConfigLists.titleKey(it.id), ConfigLists.detailKey(it.id)) }
        } + "guide_firmware_note"

        keys.filter { es[it].isNullOrBlank() }.shouldBeEmpty()
        keys.filter { en[it].isNullOrBlank() }.shouldBeEmpty()
    }

    @Test
    fun `S043 RF-2 la nota de firmware dice que se redacto sin numero de version`() {
        es.getValue("guide_firmware_note").contains("2026") shouldBe true
        es.getValue("guide_firmware_note").contains("sin número de versión") shouldBe true
        en.getValue("guide_firmware_note").contains("2026") shouldBe true
    }

    @Test
    fun `S043 RNF-T14 ningun texto de la guia menciona al fabricante`() {
        val guideTexts = (es + en).filterKeys { it.startsWith("guide_") }.values

        val brands = listOf("Emlid", "Reach Panel", "Emlid Flow")
        guideTexts.filter { text -> brands.any { text.contains(it, ignoreCase = true) } }.shouldBeEmpty()
    }
}

package es.topophone.app.ui.web

import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressProblem
import es.topophone.core.model.wms.WmsAddressResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import org.junit.Test

/**
 * El tráfico en claro está permitido para la interfaz web del receptor (IP privada), así que RNF-T4 se hace cumplir en el código: este
 * test recorre el manifiesto y los clientes HTTP de la app y comprueba que ninguno acepta HTTP a una dirección pública (S043/RF-14,
 * RNF-T4; plan D10).
 */
class CleartextPolicyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "app").isDirectory }

    private fun mainSources(): List<File> = listOf("app", "core", "data", "receiver").flatMap { module ->
        File(root, module).walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPath }.toList()
    }

    @Test
    fun `S043 RNF-T4 el manifiesto permite el trafico en claro y dice donde se limita`() {
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()

        manifest shouldContain "android:usesCleartextTraffic=\"true\""
        manifest shouldContain "WebAddressPolicy"
        manifest shouldContain "CleartextPolicyTest"
    }

    @Test
    fun `S043 RNF-T4 ningun codigo de la app escribe una URL http salvo la de la vista web y el espacio de nombres de KML`() {
        val allowed = setOf("PrivateAddress.kt", "WebAddressPolicy.kt", "KmlWriter.kt")
        val offenders = mainSources().filter { it.name !in allowed }.filter { file ->
            file.readLines().any { line -> "\"http://" in line || "'http://" in line }
        }.map { it.name }

        offenders.shouldBeEmpty()
    }

    @Test
    fun `S043 RNF-T4 el cliente de los servicios WMS no acepta HTTP a una direccion publica`() {
        listOf("http://8.8.8.8/wms", "http://wms.example.org/service", "http://1.1.1.1:8080/").forEach {
            WmsAddress.parse(it) shouldBe WmsAddressResult.Invalid(WmsAddressProblem.PUBLIC_HTTP)
        }
        (WmsAddress.parse("http://192.168.1.50/wms") is WmsAddressResult.Valid) shouldBe true
    }

    @Test
    fun `S043 RNF-T4 la vista web solo admite IP privadas de la misma direccion`() {
        val policy = WebAddressPolicy("192.168.42.1")

        listOf("http://8.8.8.8/", "http://example.org/", "http://192.168.42.2/").none { policy.allows(it) } shouldBe true
        policy.allows("http://192.168.42.1/") shouldBe true
    }
}

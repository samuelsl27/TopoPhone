package es.topophone.app.experimental

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Test

/**
 * S044 (RNF-T7, RNF-T14): los textos del flavor experimental están en `strings.xml` en español e inglés, ninguno nombra al fabricante y
 * el código del flavor no escribe textos visibles. `checkHardcodedUiText` y `StringsParityTest` lo vigilan también en `check`.
 */
class ExperimentalTextsTest {

    private val flavor = File("src/experimental")

    private fun strings(locale: String): String = File(flavor, "res/$locale/strings.xml").readText()

    private fun kotlinFiles(): List<File> = File(flavor, "kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `S044 RNF-T14 ningun texto del flavor nombra al fabricante ni usa su marca`() {
        listOf("values", "values-en").forEach { locale ->
            val text = strings(locale).lowercase()
            listOf("emlid", "reach").forEach { brand -> (brand in text) shouldBe false }
        }
    }

    @Test
    fun `S044 RNF-T7 el flavor tiene textos en espanol e ingles con las mismas claves`() {
        val key = Regex("""<string\s+name="([^"]+)"(?![^>]*translatable="false")""")
        val spanish = key.findAll(strings("values")).map { it.groupValues[1] }.toSet()
        val english = key.findAll(strings("values-en")).map { it.groupValues[1] }.toSet()

        (spanish.size > 100) shouldBe true
        (spanish - english).shouldBeEmpty()
        (english - spanish).shouldBeEmpty()
    }

    @Test
    fun `S044 RNF-T7 el codigo del flavor no escribe ninguna cadena visible`() {
        val literal = Regex("""(?:\bText\(\s*|\b(?:text|contentDescription|label|title|placeholder|supportingText)\s*=\s*)"[^"]""")
        val offenders = kotlinFiles().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && literal.containsMatchIn(line) }
                .map { (index, _) -> "${file.name}:${index + 1}" }
        }

        kotlinFiles().isNotEmpty() shouldBe true
        offenders.shouldBeEmpty()
    }

    @Test
    fun `S044 RNF-T14 ningun codigo del flavor nombra al fabricante`() {
        val offenders = kotlinFiles().filter { file ->
            file.readLines().any { line -> "emlid" in line.lowercase() }
        }.map { it.name }

        offenders.shouldBeEmpty()
    }

    @Test
    fun `S044 RNF-T7 solo hay recursos de texto en espanol por defecto e ingles`() {
        val localeDirs = File(flavor, "res").listFiles().orEmpty().filter {
            it.isDirectory && it.name.startsWith("values-")
        }.map { it.name }

        localeDirs.filterNot { it == "values-en" }.shouldBeEmpty()
    }
}

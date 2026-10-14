package es.topophone.app

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

/**
 * Cada texto visible está en español (por defecto) y en inglés (S001/RF-19).
 * Complementa a Lint (`MissingTranslation`, `ExtraTranslation`) con una comprobación directa de los ficheros.
 */
class StringsParityTest {

    /** Módulos con textos de interfaz, relativos al módulo `app` (directorio de trabajo de los tests). */
    private val resourceDirs = listOf("src/main/res", "../core/designsystem/src/main/res").map(::File)

    private fun translatableKeys(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.documentElement.childNodes
        return (0 until nodes.length)
            .map(nodes::item)
            .filter { it.nodeName == "string" || it.nodeName == "plurals" || it.nodeName == "string-array" }
            .filterNot { it.attributes.getNamedItem("translatable")?.nodeValue == "false" }
            .map { it.attributes.getNamedItem("name").nodeValue }
            .toSet()
    }

    @Test
    fun `S001 RF-19 los textos en espanol e ingles tienen las mismas claves en cada modulo`() {
        resourceDirs.forEach { dir ->
            val spanish = translatableKeys(dir.resolve("values/strings.xml"))
            val english = translatableKeys(dir.resolve("values-en/strings.xml"))

            (spanish - english).shouldBeEmpty()
            (english - spanish).shouldBeEmpty()
        }
    }

    @Test
    fun `S001 RF-19 solo hay recursos de texto en espanol (por defecto) e ingles`() {
        resourceDirs.forEach { dir ->
            val localeDirs = dir.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values-") && it.resolve("strings.xml").exists() }
                .map { it.name }

            localeDirs.filterNot { it == "values-en" }.shouldBeEmpty()
        }
    }

    @Test
    fun `S001 RF-19 los ficheros de recursos de texto existen en todos los modulos con interfaz`() {
        resourceDirs.forEach { dir -> dir.resolve("values/strings.xml").exists() shouldBe true }
    }
}

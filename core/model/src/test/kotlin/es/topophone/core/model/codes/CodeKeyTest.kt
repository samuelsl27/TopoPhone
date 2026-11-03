package es.topophone.core.model.codes

import es.topophone.core.model.project.NameError
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La clave de comparación de códigos y nombres de biblioteca (S023/RF-2, RF-6). */
class CodeKeyTest {

    @Test
    fun `S023 RF-6 la clave no distingue mayusculas, acentos ni espacios en los extremos`() {
        CodeKey.of("  Árbol ") shouldBe CodeKey.of("arbol")
        CodeKey.of("ARQ") shouldBe CodeKey.of("arq")
        CodeKey.of("AR Q") shouldBe "ar q"
    }

    @Test
    fun `S023 RF-6 dos codigos distintos tienen claves distintas`() {
        (CodeKey.of("ARQ1") == CodeKey.of("ARQ2")) shouldBe false
    }

    @Test
    fun `S023 RF-2 el nombre de una biblioteca nueva es el primero libre`() {
        LibraryNames.uniqueName("Calles", listOf("Otra")) shouldBe "Calles"
        LibraryNames.uniqueName("Calles", listOf("calles")) shouldBe "Calles (2)"
        LibraryNames.uniqueName("Calles", listOf("Calles", "calles (2)")) shouldBe "Calles (3)"
    }

    @Test
    fun `S023 RF-2 un nombre vacio o repetido no vale`() {
        LibraryNames.validate("  ", listOf("A")) shouldBe NameError.EMPTY
        LibraryNames.validate("a ", listOf("A")) shouldBe NameError.DUPLICATE
        LibraryNames.validate("B", listOf("A")) shouldBe null
    }
}

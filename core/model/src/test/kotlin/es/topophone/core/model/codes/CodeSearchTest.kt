package es.topophone.core.model.codes

import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

/** El buscador y el orden de los códigos de una biblioteca (S023/RF-11). */
class CodeSearchTest {

    private fun code(code: String, description: String = "") = SurveyCode("id-$code", code, description, CodeGeometry.POINT, 0x1E88E5)

    private val codes = listOf(
        code("POZ", "Pozo de registro"),
        code("ARB", "Árbol"),
        code("ARQ", "Arqueta de saneamiento"),
        code("FAR", "Farola"),
    )

    @Test
    fun `S023 RF-11 la lista se ordena por el codigo sin distinguir mayusculas ni acentos`() {
        CodeSearch.index(codes + code("árbol2")).filter("").map { it.code } shouldBe listOf("ARB", "árbol2", "ARQ", "FAR", "POZ")
    }

    @Test
    fun `S023 RF-11 el buscador encuentra el texto en el codigo`() {
        CodeSearch.index(codes).filter("ar").map { it.code } shouldBe listOf("ARB", "ARQ", "FAR")
    }

    @Test
    fun `S023 RF-11 el buscador encuentra el texto en la descripcion sin acentos`() {
        CodeSearch.index(codes).filter("ARBOL").map { it.code } shouldBe listOf("ARB")
        CodeSearch.index(codes).filter("  SANEAMIENTO ").map { it.code } shouldBe listOf("ARQ")
    }

    @Test
    fun `S023 RF-11 un texto que no esta en ningun codigo no muestra nada`() {
        CodeSearch.index(codes).filter("zzz") shouldBe emptyList()
    }

    @Test
    fun `S023 RF-11 una biblioteca de 2000 codigos se indexa y se busca en menos de 1 s`() {
        val big = (1..2000).map { code("C%04d".format(it), "Descripción número $it del código") }

        val millis = measureTimeMillis {
            val index = CodeSearch.index(big)
            index.filter("").size shouldBe 2000
            index.filter("número 19").size shouldBe 111
            index.filter("c0007").map { it.code } shouldBe listOf("C0007")
        }

        (millis < 1000) shouldBe true
    }
}

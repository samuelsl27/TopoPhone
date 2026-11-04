package es.topophone.core.model.codes

import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El filtro del selector de códigos y la descripción propuesta (S024/RF-2, RF-4). */
class CodeSelectorTest {

    private fun code(code: String, description: String = "") = SurveyCode("id-$code", code, description, CodeGeometry.POINT, 0x1E88E5)

    private val available = listOf(code("ARB", "Árbol"), code("ARQ", "Arqueta"), code("POS", "Poste"), code("TER", "Terreno"))

    @Test
    fun `S024 RF-2 sin texto salen los recientes primero y el resto por orden alfabetico`() {
        val selector = CodeSelector(available, listOf("TER", "arq"))

        selector.filter("").map { it.code } shouldBe listOf("TER", "ARQ", "ARB", "POS")
    }

    @Test
    fun `S024 RF-2 el texto filtra por codigo o descripcion sin mayusculas ni acentos`() {
        val selector = CodeSelector(available, listOf("TER"))

        selector.filter("ARBOL").map { it.code } shouldBe listOf("ARB")
        selector.filter("ar").map { it.code } shouldBe listOf("ARB", "ARQ")
        selector.filter("pos").map { it.code } shouldBe listOf("POS")
        selector.filter("zzz") shouldBe emptyList()
    }

    @Test
    fun `S024 RF-2 con 2000 codigos el filtro tarda menos de 16 ms`() {
        val many = (1..2000).map { code("COD%04d".format(it), "descripcion $it") }
        val selector = CodeSelector(many, listOf("COD0005"))
        repeat(20) { selector.filter("cod12") }

        val start = System.nanoTime()
        selector.filter("cod12")
        val ms = (System.nanoTime() - start) / 1_000_000

        ms shouldBeLessThan 16L
    }

    @Test
    fun `S024 RF-4 con la descripcion vacia se propone la del codigo`() {
        DescriptionProposal.propose("", code("ARB", "Árbol")) shouldBe "Árbol"
        DescriptionProposal.propose("   ", code("ARB", "Árbol")) shouldBe "Árbol"
    }

    @Test
    fun `S024 RF-4 una descripcion escrita no se pisa y sin codigo o sin descripcion queda igual`() {
        DescriptionProposal.propose("esquina", code("ARB", "Árbol")) shouldBe "esquina"
        DescriptionProposal.propose("", null) shouldBe ""
        DescriptionProposal.propose("", code("ARB", "")) shouldBe ""
    }
}

package es.topophone.core.model.codes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Qué entra al importar códigos en una biblioteca y qué se omite o reemplaza (S023/RF-6, RF-16). */
class CodeMergeTest {

    private fun code(id: String, code: String, description: String = "") = SurveyCode(id, code, description, CodeGeometry.POINT, 0x1E88E5)

    private val existing = listOf(code("e1", "ARQ", "vieja"), code("e2", "Árbol"))

    @Test
    fun `S023 RF-16 sin choques entra todo y no hay nada que decidir`() {
        val incoming = listOf(code("n1", "FAR"), code("n2", "POZ"))

        CodeMerge.conflicts(existing, incoming).isEmpty() shouldBe true
        val plan = CodeMerge.plan(existing, incoming, DuplicatePolicy.SKIP)
        plan.toAdd.map { it.code } shouldBe listOf("FAR", "POZ")
        plan.toReplace shouldBe emptyList()
        plan.skipped shouldBe 0
    }

    @Test
    fun `S023 RF-16 los choques con la biblioteca se listan sin distinguir mayusculas ni acentos`() {
        val incoming = listOf(code("n1", "arq"), code("n2", "ARBOL"), code("n3", "FAR"))

        val conflicts = CodeMerge.conflicts(existing, incoming)

        conflicts.withLibrary shouldBe listOf("arq", "ARBOL")
        conflicts.inFile shouldBe emptyList()
    }

    @Test
    fun `S023 RF-16 los repetidos del propio fichero se listan una vez cada uno`() {
        val incoming = listOf(code("n1", "FAR"), code("n2", "far"), code("n3", "Far "), code("n4", "POZ"))

        CodeMerge.conflicts(existing, incoming).inFile shouldBe listOf("far")
    }

    @Test
    fun `S023 RF-16 omitir conserva la primera aparicion y lo que ya habia`() {
        val incoming = listOf(code("n1", "ARQ", "nueva"), code("n2", "FAR", "primera"), code("n3", "far", "segunda"))

        val plan = CodeMerge.plan(existing, incoming, DuplicatePolicy.SKIP)

        plan.toAdd.map { it.description } shouldBe listOf("primera")
        plan.toReplace shouldBe emptyList()
        plan.skipped shouldBe 2
    }

    @Test
    fun `S023 RF-16 reemplazar deja la ultima aparicion y sustituye lo que ya habia con el mismo id`() {
        val incoming = listOf(code("n1", "arq", "nueva"), code("n2", "FAR", "primera"), code("n3", "far", "segunda"))

        val plan = CodeMerge.plan(existing, incoming, DuplicatePolicy.REPLACE)

        plan.toAdd.map { it.description } shouldBe listOf("segunda")
        plan.toReplace.map { it.id to it.description } shouldBe listOf("e1" to "nueva")
        plan.toReplace.single().code shouldBe "arq"
        plan.skipped shouldBe 1
    }

    @Test
    fun `S023 RF-6 nunca resultan dos codigos con la misma clave`() {
        val incoming = listOf(code("n1", "X"), code("n2", "x"), code("n3", "ARQ"), code("n4", "arq"))
        DuplicatePolicy.entries.forEach { policy ->
            val plan = CodeMerge.plan(existing, incoming, policy)
            val result =
                existing.filter { e -> plan.toReplace.none { CodeKey.of(it.code) == CodeKey.of(e.code) } } + plan.toReplace + plan.toAdd

            result.map { CodeKey.of(it.code) }.toSet().size shouldBe result.size
        }
    }
}

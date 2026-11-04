package es.topophone.core.model.codes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los códigos del proyecto y los recientes (S024/RF-1). */
class ProjectCodesTest {

    private fun code(code: String, geometry: CodeGeometry = CodeGeometry.POINT) =
        SurveyCode("id-$code", code, "desc $code", geometry, 0x1E88E5)

    @Test
    fun `S024 RF-1 los codigos del proyecto son los de la biblioteca y los propios, solo Punto, por orden alfabetico`() {
        val library = listOf(code("POS"), code("CAM", CodeGeometry.LINE), code("ARB"))
        val own = listOf(code("Árbol2"), code("EDI", CodeGeometry.POLYGON))

        ProjectCodes.available(library, own).map { it.code } shouldBe listOf("ARB", "Árbol2", "POS")
    }

    @Test
    fun `S024 RF-1 un codigo repetido por clave se ofrece una sola vez`() {
        val library = listOf(code("ARB"))
        val own = listOf(code("arb"))

        ProjectCodes.available(library, own).map { it.code } shouldBe listOf("ARB")
    }

    @Test
    fun `S024 RF-1 los recientes son los 10 ultimos distintos, el mas reciente primero`() {
        val used = listOf("A", "b", "a", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L")

        RecentCodes.distinct(used) shouldBe listOf("A", "b", "C", "D", "E", "F", "G", "H", "I", "J")
    }

    @Test
    fun `S024 RF-1 un reciente que ya no esta entre los del proyecto no se ofrece`() {
        val available = listOf(code("ARB"), code("POS"))

        RecentCodes.resolve(listOf("pos", "GONE", "arb"), available).map { it.code } shouldBe listOf("POS", "ARB")
    }
}

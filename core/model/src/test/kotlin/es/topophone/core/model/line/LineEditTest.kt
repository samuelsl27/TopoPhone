package es.topophone.core.model.line

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Editar los vértices de una línea y su PK (S025/RF-16, RF-17). */
class LineEditTest {

    private val ids = listOf("a", "b", "c")

    @Test
    fun `S025 RF-16 se anade un punto al final o entre dos vertices`() {
        LineEdit.append(ids, "d") shouldBe listOf("a", "b", "c", "d")
        LineEdit.insert(ids, 1, "x") shouldBe listOf("a", "x", "b", "c")
        LineEdit.insert(ids, 3, "x") shouldBe listOf("a", "b", "c", "x")
    }

    @Test
    fun `S025 RF-16 se quita un vertice y no pasa nada si no existe`() {
        LineEdit.remove(ids, 1) shouldBe listOf("a", "c")
        LineEdit.remove(ids, 7) shouldBe ids
    }

    @Test
    fun `S025 RF-16 se cambia el punto de un vertice`() {
        LineEdit.replace(ids, 2, "z") shouldBe listOf("a", "b", "z")
    }

    @Test
    fun `S025 RF-16 se mueve un vertice en el orden`() {
        LineEdit.move(ids, 0, 2) shouldBe listOf("b", "c", "a")
        LineEdit.move(ids, 2, 0) shouldBe listOf("c", "a", "b")
        LineEdit.move(ids, 1, 1) shouldBe ids
    }

    @Test
    fun `S025 RF-16 se invierte el sentido de la linea`() {
        LineEdit.reverse(ids) shouldBe listOf("c", "b", "a")
    }

    @Test
    fun `S025 RF-17 editar no modifica la lista original`() {
        LineEdit.remove(ids, 0)

        ids shouldBe listOf("a", "b", "c")
    }

    @Test
    fun `S025 RF-16 el PK parte del primer vertice y suma distancias horizontales`() {
        val pk = LineChainage.of(listOf(0.0 to 0.0, 3.0 to 4.0, 3.0 to 10.0))

        pk[0] shouldBe 0.0
        pk[1] shouldBe (5.0 plusOrMinus 1e-9)
        pk[2] shouldBe (11.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S025 RF-16 la longitud de un poligono incluye el lado de cierre`() {
        val square = listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0)

        LineChainage.length(square, closed = false) shouldBe (30.0 plusOrMinus 1e-9)
        LineChainage.length(square, closed = true) shouldBe (40.0 plusOrMinus 1e-9)
    }
}

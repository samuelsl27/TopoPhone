package es.topophone.core.formats.photo

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PhotoArchiveNamesTest {

    private fun names(vararg points: Pair<String, Int>) = PhotoArchiveNames.of(points.map { PhotoNameRef(it.first, it.second) })

    @Test
    fun `S056 RF-5 el nombre de cada foto es el del punto, la posicion de la foto y jpg`() {
        names("P1" to 3) shouldBe listOf(listOf("P1_1.jpg", "P1_2.jpg", "P1_3.jpg"))
    }

    @Test
    fun `S056 RF-5 el nombre del punto pierde los acentos y las enes`() {
        PhotoArchiveNames.sanitize("Árbol ñandú") shouldBe "Arbol_nandu"
        PhotoArchiveNames.sanitize("Café Ça Üstü") shouldBe "Cafe_Ca_Ustu"
    }

    @Test
    fun `S056 RF-5 lo que no es letra, cifra, guion o guion bajo pasa a guion bajo`() {
        PhotoArchiveNames.sanitize("Esq. N/E (3)") shouldBe "Esq__N_E__3_"
        PhotoArchiveNames.sanitize("a-b_c") shouldBe "a-b_c"
        PhotoArchiveNames.sanitize("日本語") shouldBe "___"
        PhotoArchiveNames.sanitize("a\\b:c*d") shouldBe "a_b_c_d"
    }

    @Test
    fun `S056 RF-5 las mayusculas se conservan`() {
        PhotoArchiveNames.sanitize("VERT-01") shouldBe "VERT-01"
    }

    @Test
    fun `S056 RF-5 un nombre en blanco es punto`() {
        PhotoArchiveNames.sanitize("") shouldBe "punto"
        PhotoArchiveNames.sanitize("   ") shouldBe "punto"
        names("" to 1) shouldBe listOf(listOf("punto_1.jpg"))
    }

    @Test
    fun `S056 RF-5 los espacios de los extremos no cuentan`() {
        PhotoArchiveNames.sanitize("  P 1  ") shouldBe "P_1"
    }

    @Test
    fun `S056 RF-5 dos puntos con el mismo nombre se distinguen con un numero al final del nombre del punto`() {
        names("A" to 2, "A" to 1, "A" to 1) shouldBe
            listOf(listOf("A_1.jpg", "A_2.jpg"), listOf("A_2_1.jpg"), listOf("A_3_1.jpg"))
    }

    @Test
    fun `S056 RF-5 los nombres se comparan sin distinguir mayusculas`() {
        names("abc" to 1, "ABC" to 1) shouldBe listOf(listOf("abc_1.jpg"), listOf("ABC_2_1.jpg"))
    }

    @Test
    fun `S056 RF-5 dos nombres distintos que se limpian igual se distinguen`() {
        names("Árbol 1" to 1, "Arbol 1" to 1, "Arbol_1" to 1) shouldBe
            listOf(listOf("Arbol_1_1.jpg"), listOf("Arbol_1_2_1.jpg"), listOf("Arbol_1_3_1.jpg"))
    }

    @Test
    fun `S056 RF-5 un punto cuyo nombre ya acaba en lo que daria otro nombre no choca con el`() {
        // «P1» con dos fotos da P1_1 y P1_2; «P1_2» con una foto daría P1_2_1: no choca. Pero un segundo «P1» daría P1_2_1, que ya está cogido.
        names("P1" to 2, "P1_2" to 1, "P1" to 1) shouldBe
            listOf(listOf("P1_1.jpg", "P1_2.jpg"), listOf("P1_2_1.jpg"), listOf("P1_3_1.jpg"))
    }

    @Test
    fun `S056 RF-5 un punto sin fotos no da nombres ni gasta el suyo`() {
        names("A" to 0, "A" to 1) shouldBe listOf(emptyList(), listOf("A_1.jpg"))
    }

    @Test
    fun `S056 RF-5 el resultado respeta el orden de los puntos y no repite ningun nombre entre mil puntos`() {
        val points = (0 until 1_000).map { "Punto ${it % 50}" to (it % 4) }

        val all = names(*points.toTypedArray()).flatten()

        all.map { it.lowercase() }.toSet().size shouldBe all.size
    }
}

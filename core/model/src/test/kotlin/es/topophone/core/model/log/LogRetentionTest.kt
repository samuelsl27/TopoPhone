package es.topophone.core.model.log

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Tamaño del log: ficheros cortados a la décima parte y los más antiguos fuera (S014/RF-8, RF-9). */
class LogRetentionTest {

    private val mb = 1024L * 1024

    @Test
    fun `S014 RF-8 cada fichero se corta a la decima parte del limite, al menos 512 kB`() {
        LogRetention.chunkBytes(10 * mb) shouldBe mb
        LogRetention.chunkBytes(50 * mb) shouldBe 5 * mb
        LogRetention.chunkBytes(4 * mb) shouldBe 512 * 1024L
    }

    @Test
    fun `S014 RF-8 al pasar del limite sobran los mas antiguos`() {
        val sizes = List(12) { mb } // 12 MB con un límite de 10 MB

        LogRetention.surplus(sizes, 10 * mb) shouldBe 2
    }

    @Test
    fun `S014 RF-8 dentro del limite no sobra ninguno`() {
        LogRetention.surplus(List(10) { mb }, 10 * mb) shouldBe 0
        LogRetention.surplus(emptyList(), 10 * mb) shouldBe 0
    }

    @Test
    fun `S014 RF-8 el fichero en uso no se borra nunca`() {
        LogRetention.surplus(listOf(mb, 30 * mb), 10 * mb) shouldBe 1
        LogRetention.surplus(listOf(30 * mb), 10 * mb) shouldBe 0
    }

    @Test
    fun `S014 RF-9 con un limite menor sobran mas ficheros en el momento`() {
        val sizes = List(20) { mb }

        LogRetention.surplus(sizes, 20 * mb) shouldBe 0
        LogRetention.surplus(sizes, 5 * mb) shouldBe 15
    }
}

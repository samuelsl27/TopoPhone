package es.topophone.core.model.point

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class PhotoPolicyTest {

    @Test
    fun `S017b RF-18 una foto apaisada de 12 MP baja a 2048 px de lado largo conservando la proporcion`() {
        PhotoPolicy.reducedSize(4_000, 3_000) shouldBe PhotoSize(2_048, 1_536)
    }

    @Test
    fun `S017b RF-18 una foto vertical baja a 2048 px de alto`() {
        PhotoPolicy.reducedSize(3_000, 4_000) shouldBe PhotoSize(1_536, 2_048)
    }

    @Test
    fun `S017b RF-18 una foto cuadrada baja a 2048 por 2048`() {
        PhotoPolicy.reducedSize(3_000, 3_000) shouldBe PhotoSize(2_048, 2_048)
    }

    @Test
    fun `S017b RF-18 una foto menor o igual que el maximo no se amplia`() {
        PhotoPolicy.reducedSize(1_600, 1_200) shouldBe PhotoSize(1_600, 1_200)
        PhotoPolicy.reducedSize(2_048, 1_000) shouldBe PhotoSize(2_048, 1_000)
    }

    @Test
    fun `S017b RF-18 un lado muy corto no baja de un pixel`() {
        PhotoPolicy.reducedSize(20_000, 5) shouldBe PhotoSize(2_048, 1)
    }

    @Test
    fun `S017b RF-13 de una seleccion caben las que faltan hasta 10 y el resto se queda fuera`() {
        PhotoPolicy.fit(existing = 9, offered = 3) shouldBe PhotoFit(accepted = 1, rejected = 2)
        PhotoPolicy.fit(existing = 0, offered = 4) shouldBe PhotoFit(accepted = 4, rejected = 0)
        PhotoPolicy.fit(existing = 0, offered = 10) shouldBe PhotoFit(accepted = 10, rejected = 0)
    }

    @Test
    fun `S017b RF-14 con 10 fotos no cabe ninguna mas`() {
        PhotoPolicy.remaining(10) shouldBe 0
        PhotoPolicy.fit(existing = 10, offered = 2) shouldBe PhotoFit(accepted = 0, rejected = 2)
        PhotoPolicy.remaining(12) shouldBe 0
    }
}

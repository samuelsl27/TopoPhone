package es.topophone.core.survey

import es.topophone.core.model.point.ElevationSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Contra qué cota se replantea: la propia o la de la superficie, y cuándo se ofrece el conmutador (S042/RF-24, RF-25). */
class ElevationSourcesTest {

    private fun resolve(surface: Boolean, own: Boolean, manual: Boolean, choice: ElevationSource? = null) =
        ElevationSources.resolve(surface, own, manual, choice)

    @Test
    fun `S042 RF-24 sin superficie activa siempre es la cota del punto y no hay conmutador`() {
        for (own in listOf(false, true)) {
            for (manual in listOf(false, true)) {
                for (choice in listOf(null, ElevationSource.POINT, ElevationSource.SURFACE)) {
                    resolve(false, own, manual, choice) shouldBe ElevationSources.Resolution(ElevationSource.POINT, canChoose = false)
                }
            }
        }
    }

    @Test
    fun `S042 RF-24 un punto sin cota con superficie activa se replantea contra la superficie`() {
        resolve(surface = true, own = false, manual = false) shouldBe
            ElevationSources.Resolution(ElevationSource.SURFACE, canChoose = false)
    }

    @Test
    fun `S042 RF-25 la superficie nunca pisa una cota propia sin que el usuario lo elija`() {
        resolve(surface = true, own = true, manual = false) shouldBe ElevationSources.Resolution(ElevationSource.POINT, canChoose = true)
    }

    @Test
    fun `S042 RF-25 una cota escrita a mano tampoco la pisa la superficie`() {
        resolve(surface = true, own = false, manual = true) shouldBe ElevationSources.Resolution(ElevationSource.POINT, canChoose = true)
        resolve(surface = true, own = true, manual = true) shouldBe ElevationSources.Resolution(ElevationSource.POINT, canChoose = true)
    }

    @Test
    fun `S042 RF-25 el usuario elige la superficie y despues vuelve a la cota del punto`() {
        resolve(true, own = true, manual = false, choice = ElevationSource.SURFACE) shouldBe
            ElevationSources.Resolution(ElevationSource.SURFACE, canChoose = true)
        resolve(true, own = true, manual = false, choice = ElevationSource.POINT) shouldBe
            ElevationSources.Resolution(ElevationSource.POINT, canChoose = true)
    }

    @Test
    fun `S042 RF-25 con la superficie elegida a mano la cota escrita no cuenta`() {
        resolve(true, own = false, manual = true, choice = ElevationSource.SURFACE).source shouldBe ElevationSource.SURFACE
    }

    @Test
    fun `S042 RF-25 si no hay cota propia ni escrita lo elegido no ofrece conmutador`() {
        // Elegir «Cota del punto» sin ninguna cota solo deja el replanteo en planta: no hay nada entre lo que elegir.
        resolve(true, own = false, manual = false, choice = ElevationSource.POINT) shouldBe
            ElevationSources.Resolution(ElevationSource.POINT, canChoose = false)
    }
}

package es.topophone.app.experimental

import es.topophone.receiver.experimental.ReachModule
import io.kotest.matchers.shouldBe
import org.junit.Test

/** S044/RF-1: el flavor `experimental` sí lleva el módulo y enlaza las ranuras a la implementación real. */
class ExperimentalFlavorTest {

    @Test
    fun `S044 RF-1 el classpath de experimental tiene el modulo`() {
        ReachModule.NAME shouldBe "receiver-experimental"
    }

    @Test
    fun `S044 RF-1 las ranuras de experimental estan disponibles`() {
        ExperimentalFeatureImpl().available shouldBe true
    }
}

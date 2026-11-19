package es.topophone.app

import es.topophone.app.experimental.ExperimentalFeatureModule
import es.topophone.app.experimental.NoExperimentalFeature
import io.kotest.matchers.shouldBe
import org.junit.Test

/** S044/RF-1: el flavor `standard` no lleva ni una clase del módulo experimental ni de su flavor. */
class StandardFlavorIsolationTest {

    private fun present(name: String): Boolean = try {
        Class.forName(name)
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    @Test
    fun `S044 RF-1 el classpath de standard no tiene el modulo experimental`() {
        present("es.topophone.receiver.experimental.ReachModule") shouldBe false
    }

    @Test
    fun `S044 RF-1 el classpath de standard no tiene la implementacion del flavor`() {
        present("es.topophone.app.experimental.ExperimentalFeatureImpl") shouldBe false
    }

    @Test
    fun `S044 RF-1 las ranuras de standard no estan disponibles`() {
        ExperimentalFeatureModule.experimentalFeature().available shouldBe false
        NoExperimentalFeature().available shouldBe false
    }
}

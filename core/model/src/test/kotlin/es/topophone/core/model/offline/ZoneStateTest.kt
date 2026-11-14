package es.topophone.core.model.offline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ZoneStateTest {

    @Test
    fun `S037 RF-24 una zona es Completa solo si todas sus fuentes lo son`() {
        ZoneState.of(listOf(SourceState.COMPLETE, SourceState.COMPLETE), updating = false) shouldBe ZoneState.COMPLETE
    }

    @Test
    fun `S037 RF-18 una zona con una fuente sin terminar es Incompleta`() {
        listOf(SourceState.PENDING, SourceState.DOWNLOADING, SourceState.PAUSED, SourceState.INCOMPLETE).forEach { state ->
            ZoneState.of(listOf(SourceState.COMPLETE, state), updating = false) shouldBe ZoneState.INCOMPLETE
        }
    }

    @Test
    fun `S037 RF-19 una fuente incompleta deja la zona incompleta aunque las demas esten completas`() {
        ZoneState.of(listOf(SourceState.INCOMPLETE, SourceState.COMPLETE), updating = false) shouldBe ZoneState.INCOMPLETE
    }

    @Test
    fun `S037 RF-26 mientras se actualiza la zona esta Actualizando`() {
        ZoneState.of(listOf(SourceState.COMPLETE), updating = true) shouldBe ZoneState.UPDATING
        ZoneState.of(listOf(SourceState.DOWNLOADING), updating = true) shouldBe ZoneState.UPDATING
    }

    @Test
    fun `S037 RF-24 una zona sin fuentes no esta completa`() {
        ZoneState.of(emptyList(), updating = false) shouldBe ZoneState.INCOMPLETE
    }
}

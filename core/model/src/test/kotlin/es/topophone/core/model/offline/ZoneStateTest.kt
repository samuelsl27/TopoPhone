package es.topophone.core.model.offline

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ZoneStateTest {

    @Test
    fun `S037 RF-24 una zona es Completa solo si todas sus fuentes lo son`() {
        ZoneState.of(listOf(SourceState.COMPLETE, SourceState.COMPLETE)) shouldBe ZoneState.COMPLETE
    }

    @Test
    fun `S037 RF-18 una zona con una fuente sin terminar es Incompleta`() {
        listOf(SourceState.PENDING, SourceState.DOWNLOADING, SourceState.PAUSED, SourceState.INCOMPLETE).forEach { state ->
            ZoneState.of(listOf(SourceState.COMPLETE, state)) shouldBe ZoneState.INCOMPLETE
        }
    }

    @Test
    fun `S037 RF-19 una fuente incompleta deja la zona incompleta aunque las demas esten completas`() {
        ZoneState.of(listOf(SourceState.INCOMPLETE, SourceState.COMPLETE)) shouldBe ZoneState.INCOMPLETE
    }

    @Test
    fun `S037 RF-24 una zona sin fuentes no esta completa`() {
        ZoneState.of(emptyList()) shouldBe ZoneState.INCOMPLETE
    }

    @Test
    fun `S037 RF-26 una zona solo es Completa o Incompleta, sin estado Actualizando`() {
        ZoneState.entries.map { it.name } shouldBe listOf("COMPLETE", "INCOMPLETE")
    }
}

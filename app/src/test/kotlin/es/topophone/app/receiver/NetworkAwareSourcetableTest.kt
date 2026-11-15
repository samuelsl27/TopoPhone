package es.topophone.app.receiver

import es.topophone.core.ntrip.SourcetableFailure
import es.topophone.core.ntrip.SourcetableResult
import es.topophone.core.ntrip.SourcetableSource
import es.topophone.receiver.platform.NoInternetException
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** La tabla de fuentes sale por la red con internet solo con un receptor Wi-Fi (S021/RF-9, RF-10). */
class NetworkAwareSourcetableTest {

    private val ok = SourcetableResult.Ok(emptyList())
    private var defaultCalls = 0
    private var routedCalls = 0
    private val default = SourcetableSource { _, _, _, _, _ ->
        defaultCalls++
        ok
    }

    private fun routed(result: () -> SourcetableResult) = SourcetableSource { _, _, _, _, _ ->
        routedCalls++
        result()
    }

    private fun fetch(source: SourcetableSource) = runBlocking { source.fetch("caster.es", 2101, "u", "p", false) }

    @Test
    fun `S021 RF-9 con Bluetooth o sin receptor se usa la fuente de siempre`() {
        val source = NetworkAwareSourcetable(default, routed { error("no debe usarse") }, wifiReceiver = { false })

        fetch(source) shouldBe ok

        defaultCalls shouldBe 1
        routedCalls shouldBe 0
    }

    @Test
    fun `S021 RF-9 con un receptor Wi-Fi se usa la fuente por la red con internet`() {
        val source = NetworkAwareSourcetable(default, routed { ok }, wifiReceiver = { true })

        fetch(source) shouldBe ok

        routedCalls shouldBe 1
        defaultCalls shouldBe 0
    }

    @Test
    fun `S021 RF-10 con un receptor Wi-Fi y sin red con internet no se puede contactar con el caster`() {
        val source = NetworkAwareSourcetable(default, routed { throw NoInternetException() }, wifiReceiver = { true })

        fetch(source) shouldBe SourcetableResult.Failed(SourcetableFailure.UNREACHABLE)
    }

    @Test
    fun `S021 RF-9 se decide en cada descarga segun el receptor de ese momento`() {
        var wifi = false
        val source = NetworkAwareSourcetable(default, routed { ok }, wifiReceiver = { wifi })

        fetch(source)
        wifi = true
        fetch(source)

        defaultCalls shouldBe 1
        routedCalls shouldBe 1
    }
}

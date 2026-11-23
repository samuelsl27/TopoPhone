package es.topophone.app.ui.collector

import es.topophone.core.model.point.AutoTrigger
import es.topophone.core.model.point.CollectorSettings
import es.topophone.core.model.point.MeasurementMode
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Las cuatro opciones de «Modo» y el valor de «Tiempo», traducidos a las acciones de siempre (S060/RF-9, RF-10, RF-13, RF-32). */
class CollectionModeChoiceTest {

    private val calls = mutableListOf<String>()

    private val actions = CollectorActions(
        onMode = { calls += "mode:${it.name}" },
        onSeconds = { calls += "seconds:$it" },
        onAutoEnabled = { calls += "auto:$it" },
        onAutoTrigger = { calls += "trigger:${it.name}" },
        onAutoInterval = { calls += "interval:$it" },
        onAutoDistance = { calls += "distance:$it" },
    )

    private fun state(
        mode: MeasurementMode = MeasurementMode.INSTANT,
        autoEnabled: Boolean = false,
        trigger: AutoTrigger = AutoTrigger.TIME,
    ) = CollectorUiState(
        loaded = true,
        settings = CollectorSettings(mode = mode, autoTrigger = trigger),
        autoEnabled = autoEnabled,
        averagingSecondsText = "0:30",
        autoIntervalText = "0:10",
        autoDistanceText = "2,500",
    )

    @Test
    fun `S060 RF-9 el modo vigente sale del modo de medida y del automatico`() {
        CollectionModes.current(state()) shouldBe CollectionMode.INSTANT
        CollectionModes.current(state(mode = MeasurementMode.AVERAGED)) shouldBe CollectionMode.AVERAGED
        CollectionModes.current(state(autoEnabled = true)) shouldBe CollectionMode.AUTO_TIME
        CollectionModes.current(state(autoEnabled = true, trigger = AutoTrigger.DISTANCE)) shouldBe CollectionMode.AUTO_DISTANCE
    }

    @Test
    fun `S060 RF-13 elegir instantaneo o promediado pone ese modo y apaga el automatico si estaba`() {
        CollectionModes.choose(CollectionMode.AVERAGED, state(), actions)
        calls shouldBe listOf("mode:AVERAGED")
        calls.clear()

        CollectionModes.choose(CollectionMode.INSTANT, state(autoEnabled = true), actions)
        calls shouldBe listOf("auto:false", "mode:INSTANT")
    }

    @Test
    fun `S060 RF-13 elegir automatico por tiempo o por distancia pone el disparo y enciende el automatico sin tocar el modo`() {
        CollectionModes.choose(CollectionMode.AUTO_DISTANCE, state(), actions)
        calls shouldBe listOf("trigger:DISTANCE", "auto:true")
        calls.clear()

        CollectionModes.choose(CollectionMode.AUTO_TIME, state(autoEnabled = true, trigger = AutoTrigger.DISTANCE), actions)
        calls shouldBe listOf("trigger:TIME")
    }

    @Test
    fun `S060 RF-10 la pastilla Tiempo muestra el promediado el intervalo la distancia o nada en instantaneo`() {
        CollectionModes.timeText(state(), "m") shouldBe null
        CollectionModes.timeText(state(mode = MeasurementMode.AVERAGED), "m") shouldBe "0:30"
        CollectionModes.timeText(state(autoEnabled = true), "m") shouldBe "0:10"
        CollectionModes.timeText(state(autoEnabled = true, trigger = AutoTrigger.DISTANCE), "m") shouldBe "2,500 m"
    }

    @Test
    fun `S060 RF-10 en instantaneo la pastilla Tiempo no tiene selector`() {
        CollectionModes.timeEditable(CollectionMode.INSTANT) shouldBe false
        CollectionMode.entries.filter { it != CollectionMode.INSTANT }.forEach { CollectionModes.timeEditable(it) shouldBe true }
    }

    @Test
    fun `S060 RF-11 el valor escrito va a la accion de siempre de cada modo`() {
        CollectionModes.applyTime(CollectionMode.AVERAGED, "1:00", actions)
        CollectionModes.applyTime(CollectionMode.AUTO_TIME, "0:20", actions)
        CollectionModes.applyTime(CollectionMode.AUTO_DISTANCE, "3,5", actions)
        CollectionModes.applyTime(CollectionMode.INSTANT, "9", actions)

        calls shouldBe listOf("seconds:1:00", "interval:0:20", "distance:3,5")
    }
}

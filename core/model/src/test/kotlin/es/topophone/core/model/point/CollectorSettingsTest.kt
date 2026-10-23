package es.topophone.core.model.point

import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Tiempo de promediado y ajustes del modo automático (S017/RF-1, RF-16, RF-17). */
class CollectorSettingsTest {

    @Test
    fun `S017 RF-1 el promediado va de 1 s a 60 min`() {
        CollectorSettings.AVERAGING_SECONDS shouldBe 1..3600
        CollectorSettings(averagingSeconds = 7200).validAveragingSeconds shouldBe 3600
        CollectorSettings(averagingSeconds = 0).validAveragingSeconds shouldBe 1
        CollectorSettings().averagingSeconds shouldBe 5
    }

    @Test
    fun `S017 RF-16 el intervalo del disparo por tiempo va de 1 s a 60 min con 5 s por defecto`() {
        CollectorSettings.AUTO_INTERVAL_SECONDS shouldBe 1..3600
        CollectorSettings().autoTrigger shouldBe AutoTrigger.TIME
        CollectorSettings().autoIntervalS shouldBe 5
        CollectorSettings(autoIntervalS = 0).validAutoIntervalS shouldBe 1
        CollectorSettings(autoIntervalS = 4000).validAutoIntervalS shouldBe 3600
    }

    @Test
    fun `S017 RF-17 la distancia del disparo va de 0,1 m a 1000 m con 1 m por defecto`() {
        CollectorSettings.AUTO_DISTANCE_M shouldBe 0.1..1000.0
        CollectorSettings().autoDistanceM shouldBe 1.0
        CollectorSettings(autoDistanceM = 0.01).validAutoDistanceM shouldBe 0.1
        CollectorSettings(autoDistanceM = 5000.0).validAutoDistanceM shouldBe 1000.0
    }

    @Test
    fun `S017 RF-17 la distancia se escribe en la unidad del proyecto y se guarda en metros`() {
        CollectorSettings.parseAutoDistance("1,5", LinearUnit.METRE) shouldBe 1.5
        CollectorSettings.parseAutoDistance("1000", LinearUnit.METRE) shouldBe 1000.0
        CollectorSettings.parseAutoDistance("10", LinearUnit.INTERNATIONAL_FOOT)!! shouldBe (3.048 plusOrMinus 1e-12)
        CollectorSettings.parseAutoDistance("10", LinearUnit.US_SURVEY_FOOT)!! shouldBe (3.048006096 plusOrMinus 1e-8)
    }

    @Test
    fun `S017 RF-17 fuera de los limites no vale, con un milimetro de margen por el redondeo`() {
        CollectorSettings.parseAutoDistance("0,05", LinearUnit.METRE).shouldBeNull()
        CollectorSettings.parseAutoDistance("1001", LinearUnit.METRE).shouldBeNull()
        CollectorSettings.parseAutoDistance("0", LinearUnit.METRE).shouldBeNull()
        CollectorSettings.parseAutoDistance("abc", LinearUnit.METRE).shouldBeNull()
        // 0,328 ft son 0,09997 m: el límite que se enseña, redondeado a 3 decimales, vale y se queda en 0,1 m.
        CollectorSettings.parseAutoDistance("0,328", LinearUnit.INTERNATIONAL_FOOT) shouldBe 0.1
        CollectorSettings.parseAutoDistance("0,3", LinearUnit.INTERNATIONAL_FOOT).shouldBeNull()
    }

    @Test
    fun `S017 RF-17 los limites se ensenan en la unidad del proyecto`() {
        CollectorSettings.autoDistanceRangeIn(LinearUnit.METRE) shouldBe 0.1..1000.0
        val feet = CollectorSettings.autoDistanceRangeIn(LinearUnit.INTERNATIONAL_FOOT)
        feet.start shouldBe (0.328084 plusOrMinus 1e-6)
        feet.endInclusive shouldBe (3280.84 plusOrMinus 1e-2)
    }

    @Test
    fun `S010 RF-13 los ajustes por defecto no cambian`() {
        val settings = CollectorSettings()
        settings.mode shouldBe MeasurementMode.INSTANT
        settings.precision shouldBe PrecisionControl()
    }
}

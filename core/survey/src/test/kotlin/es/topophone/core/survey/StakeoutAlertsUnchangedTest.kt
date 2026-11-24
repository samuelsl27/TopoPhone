package es.topophone.core.survey

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Los avisos sonoros y la vibración del replanteo no cambian con la placa de guiado (S060/RF-31): salen de las zonas y del nivel de
 * S018 y S019, no de lo que se dibuja, y «En el punto» de la placa no añade ni quita ninguno.
 */
class StakeoutAlertsUnchangedTest {

    private val tolerance = 0.025
    private val toleranceV = 0.03

    @Test
    fun `S060 RF-31 el aviso de acercarse sale a 40 cm y el de llegada dentro de la tolerancia`() {
        StakeoutZones.AID_RADIUS_M shouldBe 0.40
        StakeoutZones.next(StakeZone.FAR, 0.40, tolerance).alert shouldBe StakeAlert.APPROACH
        StakeoutZones.next(StakeZone.AID, 0.025, tolerance).alert shouldBe StakeAlert.ARRIVED
        StakeoutZones.next(StakeZone.FAR, 0.02, tolerance).alert shouldBe StakeAlert.ARRIVED
    }

    @Test
    fun `S060 RF-31 el aviso de salir de la tolerancia pide 5 mm de margen y no se repite en el borde`() {
        StakeoutZones.MARGIN_M shouldBe 0.005
        StakeoutZones.next(StakeZone.INSIDE, 0.028, tolerance).alert shouldBe null
        StakeoutZones.next(StakeZone.INSIDE, 0.031, tolerance).alert shouldBe StakeAlert.LEFT
    }

    @Test
    fun `S060 RF-31 los avisos del nivel vertical entran en cota y salen con 5 mm de margen`() {
        StakeoutLevel.MARGIN_M shouldBe 0.005
        StakeoutLevel.next(LevelState.CUT, 0.02, toleranceV).alert shouldBe LevelAlert.ARRIVED
        StakeoutLevel.next(LevelState.ON_GRADE, 0.033, toleranceV).alert shouldBe null
        StakeoutLevel.next(LevelState.ON_GRADE, 0.036, toleranceV).alert shouldBe LevelAlert.LEFT
    }

    @Test
    fun `S060 RF-31 la tolerancia por defecto sigue siendo 2 5 cm la horizontal y 3 cm la vertical`() {
        es.topophone.core.model.point.StakeoutSettings.DEFAULT_TOLERANCE_M shouldBe 0.025
        es.topophone.core.model.point.StakeoutSettings.DEFAULT_TOLERANCE_V_M shouldBe 0.03
    }
}

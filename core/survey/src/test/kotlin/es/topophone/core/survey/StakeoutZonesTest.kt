package es.topophone.core.survey

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Las zonas de la guía y sus avisos (S018/RF-19, RF-20, RF-21). */
class StakeoutZonesTest {

    private val tolerance = 0.025

    /** Recorre las distancias desde [from] y devuelve las zonas y los avisos que salen. */
    private fun run(from: StakeZone, vararg distances: Double, tolerance: Double = this.tolerance): List<ZoneStep> {
        var zone = from
        return distances.map { d -> StakeoutZones.next(zone, d, tolerance).also { zone = it.zone } }
    }

    @Test
    fun `S018 RF-19 lejos, en la zona de ayuda y dentro de la tolerancia segun la distancia`() {
        StakeoutZones.next(StakeZone.FAR, 3.0, tolerance).zone shouldBe StakeZone.FAR
        StakeoutZones.next(StakeZone.FAR, 0.40, tolerance).zone shouldBe StakeZone.AID
        StakeoutZones.next(StakeZone.FAR, 0.026, tolerance).zone shouldBe StakeZone.AID
        StakeoutZones.next(StakeZone.FAR, 0.025, tolerance).zone shouldBe StakeZone.INSIDE
        StakeoutZones.next(StakeZone.FAR, 0.0, tolerance).zone shouldBe StakeZone.INSIDE
    }

    @Test
    fun `S018 RF-21 al entrar en la zona de ayuda desde fuera avisa una vez`() {
        val steps = run(StakeZone.FAR, 1.0, 0.5, 0.39, 0.35, 0.30)

        steps.map { it.alert } shouldBe listOf(null, null, StakeAlert.APPROACH, null, null)
        steps.map { it.zone } shouldBe listOf(StakeZone.FAR, StakeZone.FAR, StakeZone.AID, StakeZone.AID, StakeZone.AID)
    }

    @Test
    fun `S018 RF-21 el aviso de la zona de ayuda no se repite por oscilar en el borde de los 40 cm`() {
        val steps = run(StakeZone.FAR, 0.399, 0.402, 0.399, 0.404, 0.398)

        steps.map { it.alert } shouldBe listOf(StakeAlert.APPROACH, null, null, null, null)
        // Solo se rearma al alejarse más de 5 mm del borde.
        run(StakeZone.AID, 0.406, 0.39).map { it.alert } shouldBe listOf(null, StakeAlert.APPROACH)
        run(StakeZone.AID, 0.406).single().zone shouldBe StakeZone.FAR
    }

    @Test
    fun `S018 RF-20 al entrar en la tolerancia avisa de que ha llegado y al salir de que se ha ido`() {
        val steps = run(StakeZone.AID, 0.05, 0.03, 0.02, 0.015, 0.03, 0.05)

        steps.map { it.alert } shouldBe listOf(null, null, StakeAlert.ARRIVED, null, null, StakeAlert.LEFT)
        steps.map { it.zone } shouldBe listOf(
            StakeZone.AID,
            StakeZone.AID,
            StakeZone.INSIDE,
            StakeZone.INSIDE,
            StakeZone.INSIDE,
            StakeZone.AID,
        )
    }

    @Test
    fun `S018 RF-20 entre la tolerancia y 5 mm mas no cambia nada, oscilando en el borde no se repite el aviso`() {
        val steps = run(StakeZone.INSIDE, 0.0251, 0.029, 0.0249, 0.0299, 0.02, 0.0301, 0.024)

        steps.map { it.alert } shouldBe listOf(null, null, null, null, null, StakeAlert.LEFT, StakeAlert.ARRIVED)
        steps.map { it.zone } shouldBe listOf(
            StakeZone.INSIDE,
            StakeZone.INSIDE,
            StakeZone.INSIDE,
            StakeZone.INSIDE,
            StakeZone.INSIDE,
            StakeZone.AID,
            StakeZone.INSIDE,
        )
    }

    @Test
    fun `S018 RF-20 al salir de la tolerancia hacia mas de 40 cm queda lejos`() {
        val step = StakeoutZones.next(StakeZone.INSIDE, 0.5, tolerance)

        step.zone shouldBe StakeZone.FAR
        step.alert shouldBe StakeAlert.LEFT
    }

    @Test
    fun `S018 RF-20 saltar de lejos a dentro avisa solo de que ha llegado`() {
        val step = StakeoutZones.next(StakeZone.FAR, 0.01, tolerance)

        step.zone shouldBe StakeZone.INSIDE
        step.alert shouldBe StakeAlert.ARRIVED
    }

    @Test
    fun `S018 RF-19 con una tolerancia de mas de 40 cm no hay zona de ayuda`() {
        val steps = run(StakeZone.FAR, 2.0, 0.9, 0.55, 0.605, 0.61, tolerance = 0.60)

        steps.map { it.zone } shouldBe listOf(StakeZone.FAR, StakeZone.FAR, StakeZone.INSIDE, StakeZone.INSIDE, StakeZone.FAR)
        steps.map { it.alert } shouldBe listOf(null, null, StakeAlert.ARRIVED, null, StakeAlert.LEFT)
    }

    @Test
    fun `S018 RF-19 con una tolerancia de exactamente 40 cm tampoco hay zona de ayuda`() {
        StakeoutZones.next(StakeZone.FAR, 0.41, 0.40).zone shouldBe StakeZone.FAR
        StakeoutZones.next(StakeZone.FAR, 0.40, 0.40).zone shouldBe StakeZone.INSIDE
    }
}

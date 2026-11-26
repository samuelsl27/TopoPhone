package es.topophone.app.ui.stakeout

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeSource
import es.topophone.core.survey.Guidance
import es.topophone.core.survey.HeadingResult
import es.topophone.core.survey.StakeZone
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * El mapa en el replanteo de la superficie: sin destino, solo la punta, para que se siga al jalón con la escala por defecto a la que se
 * ve la malla, y nunca hacia las coordenadas de relleno del objetivo (S062/RF-12).
 */
class SurfaceStakeFollowTest {

    private val target = StakeTarget(
        "surface:s1",
        "explanacion",
        "",
        PointCoordinates(GeodeticPosition(0.0, 0.0, null), null, null, null),
        StakeSource.Surface("explanacion"),
    )

    private fun on(tip: GeodeticPosition) = GuidanceState.On(
        GuidanceUi(
            guidance = Guidance(0.0, 0.0, 0.0, null, null, null, null),
            zone = StakeZone.FAR,
            heading = HeadingResult.Available(0.0),
            tipEastM = 0.0,
            tipNorthM = 0.0,
            dianaRadiusM = 0.2,
            solution = SolutionStatus.FIX,
            horizontalAccuracyM = 0.012,
            tipUncompensated = false,
            tip = tip,
            surfaceOnly = true,
        ),
    )

    @Test
    fun `S062 RF-12 sin destino solo va la punta`() {
        val state = StakeoutUiState(target = target, surfaceOnly = true, guidance = on(GeodeticPosition(40.4168, -3.7038, 700.0)))

        val marker = StakeoutMarkers.of(state)

        marker?.design shouldBe null
        marker?.tip shouldBe GeoPoint(40.4168, -3.7038)
        marker?.line shouldBe false
    }

    @Test
    fun `S062 RF-12 sin guia todavia no hay nada que seguir ni destino a 0 0`() {
        val state = StakeoutUiState(target = target, surfaceOnly = true)

        val marker = StakeoutMarkers.of(state)

        marker?.design shouldBe null
        marker?.tip shouldBe null
    }
}

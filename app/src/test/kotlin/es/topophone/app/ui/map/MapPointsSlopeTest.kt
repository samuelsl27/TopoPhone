package es.topophone.app.ui.map

import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Los puntos de talud replanteados llevan su tipo al mapa para dibujarse con un símbolo propio (S051/RF-16). */
class MapPointsSlopeTest {

    private fun summary(id: String, origin: PointOrigin, slope: SlopeKind? = null, hidden: Boolean = false) =
        PointSummary(id, id, "", origin, 40.0, -3.0, true, false, false, hidden, 0, slopeKind = slope)

    @Test
    fun `S051 RF-16 un punto de talud lleva su tipo y sigue siendo un replanteo`() {
        val points = MapPoints.from(
            listOf(
                summary("cut", PointOrigin.STAKED, SlopeKind.CUT),
                summary("fill", PointOrigin.STAKED, SlopeKind.FILL),
                summary("axis", PointOrigin.STAKED),
                summary("measured", PointOrigin.MEASURED),
            ),
        ).associateBy { it.id }

        points.getValue("cut").slope shouldBe SlopeKind.CUT
        points.getValue("fill").slope shouldBe SlopeKind.FILL
        points.getValue("cut").staked shouldBe true
        points.getValue("axis").slope.shouldBeNull()
        points.getValue("measured").slope.shouldBeNull()
    }

    @Test
    fun `S051 RF-16 un punto de talud oculto no va al mapa`() {
        MapPoints.from(listOf(summary("cut", PointOrigin.STAKED, SlopeKind.CUT, hidden = true))) shouldBe emptyList()
    }
}

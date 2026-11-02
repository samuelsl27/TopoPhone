package es.topophone.core.survey

import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** La punta del jalón frente al talud de diseño: diferencia de cota, distancia a moverse, error en PK y «encontrado» (S051/RF-11 a RF-14). */
class SlopeLiveTest {
    private val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje C").elements)
    private val rule = SlopeRule(SlopeSide.RIGHT, 0.0, 200.0, cutRatio = 1.5, fillRatio = 2.0)
    private val edge = SlopeEdgeResult.Edge(rule, 105.0, 3.5, 100.0, geometry.pointAt(105.0, 3.5)!!)

    /** La punta a [t] metros hacia fuera del borde (el eje va al Este: la derecha es el Sur) y [dPk] metros más allá del PK de diseño. */
    private fun live(
        t: Double,
        height: Double,
        dPk: Double = 0.0,
        kind: SlopeKind? = null,
        edge: SlopeEdgeResult.Edge = this.edge,
        horizontal: Double = 0.02,
        vertical: Double = 0.05,
    ) = SlopeLive.of(edge, kind, 441_000.0 + 105.0 + dPk, 4_474_000.0 - (3.5 + t), height, geometry, horizontal, vertical)

    private fun slope(kind: SlopeKind, t: Double) = if (kind == SlopeKind.CUT) 100.0 + t / 1.5 else 100.0 - t / 2.0

    @Test
    fun `S051 RF-11 una punta por encima del talud de desmonte pide ir hacia fuera hasta que las cotas coincidan`() {
        val v = live(t = 2.0, height = 101.5).shouldBeInstanceOf<SlopeLiveValue>()

        v.kind shouldBe SlopeKind.CUT
        v.slopeHeightM shouldBe (101.0 + 1.0 / 3 plusOrMinus 1e-9)
        v.dHeightM shouldBe (101.5 - (100.0 + 2.0 / 1.5) plusOrMinus 1e-9)
        v.moveM shouldBe (1.5 * v.dHeightM plusOrMinus 1e-9)
        v.moveM shouldBe (0.25 plusOrMinus 1e-9)
    }

    @Test
    fun `S051 RF-11 moverse la distancia dada hace que la cota del talud sea la de la punta`() {
        for ((kind, t, h) in listOf(
            Triple(SlopeKind.CUT, 2.0, 101.5),
            Triple(SlopeKind.CUT, 6.0, 101.2),
            Triple(SlopeKind.FILL, 1.0, 98.9),
            Triple(SlopeKind.FILL, 4.0, 99.9),
        )) {
            val v = live(t, h, kind = kind).shouldBeInstanceOf<SlopeLiveValue>()

            slope(kind, t + v.moveM) shouldBe (h plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S051 RF-11 una punta por debajo del talud de terraplen pide ir hacia fuera y una por encima hacia dentro`() {
        val below = live(t = 1.0, height = 98.9, kind = SlopeKind.FILL).shouldBeInstanceOf<SlopeLiveValue>()
        val above = live(t = 1.0, height = 99.9, kind = SlopeKind.FILL).shouldBeInstanceOf<SlopeLiveValue>()

        below.moveM shouldBe (1.2 plusOrMinus 1e-9)
        above.moveM shouldBe (-2.0 * (99.9 - 99.5) plusOrMinus 1e-9)
        (above.moveM < 0) shouldBe true
    }

    @Test
    fun `S051 RF-11 sin tipo conocido el tipo lo decide la cota de la punta respecto a la del borde`() {
        live(t = 2.0, height = 101.0)!!.kind shouldBe SlopeKind.CUT
        live(t = 2.0, height = 99.0)!!.kind shouldBe SlopeKind.FILL
    }

    @Test
    fun `S051 RF-12 el error en PK es el PK de la punta menos el de diseno`() {
        live(t = 2.0, height = 101.5, dPk = 0.03)!!.dChainageM shouldBe (0.03 plusOrMinus 1e-9)
        live(t = 2.0, height = 101.5, dPk = -0.5)!!.dChainageM shouldBe (-0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `S051 RF-13 con las cotas coincidiendo y la punta en la linea de la seccion el punto esta encontrado`() {
        live(t = 2.25, height = 101.5)!!.found shouldBe true
        live(t = 2.25, height = 101.5 + 0.049)!!.found shouldBe true
        live(t = 2.25, height = 101.5 + 0.051)!!.found shouldBe false
    }

    @Test
    fun `S051 RF-12 y RF-13 si el error en PK pasa la tolerancia horizontal no se da por encontrado ni llegada`() {
        val v = live(t = 2.25, height = 101.5, dPk = 0.03)!!

        v.onLine shouldBe false
        v.found shouldBe false
        live(t = 2.25, height = 101.5, dPk = 0.01)!!.onLine shouldBe true
    }

    @Test
    fun `S051 RF-11 una punta antes del inicio o despues del final del eje no tiene proyeccion`() {
        SlopeLive.of(edge, null, 440_990.0, 4_473_990.0, 101.0, geometry, 0.02, 0.05).shouldBeNull()
    }

    @Test
    fun `S051 RF-14 el punto de estaca esta fuera del punto de talud sobre la misma seccion`() {
        val catchAt = SlopeCatchResult.Found(SlopeKind.CUT, 1.5, 5.0, 101.0, 1.0, geometry.pointAt(105.0, 5.0)!!)

        val stake = SlopeLive.stake(edge, catchAt, 1.0, geometry).shouldBeInstanceOf<SlopeStake>()

        stake.offsetM shouldBe 6.0
        stake.point.easting shouldBe (441_105.0 plusOrMinus 1e-9)
        stake.point.northing shouldBe (4_474_000.0 - 6.0 plusOrMinus 1e-9)
        stake.distanceToCatchM shouldBe 1.0
        stake.slopeHeightM shouldBe 1.0
    }

    @Test
    fun `S051 RF-14 la estaca a cero es el propio punto de talud y a la izquierda se aleja hacia el otro lado`() {
        val catchAt = SlopeCatchResult.Found(SlopeKind.FILL, 2.0, -5.5, 99.0, 1.0, geometry.pointAt(105.0, -5.5)!!)
        val leftEdge = SlopeEdgeResult.Edge(rule.copy(side = SlopeSide.LEFT), 105.0, -3.5, 100.0, geometry.pointAt(105.0, -3.5)!!)

        SlopeLive.stake(leftEdge, catchAt, 0.0, geometry)!!.offsetM shouldBe -5.5
        SlopeLive.stake(leftEdge, catchAt, 2.0, geometry)!!.offsetM shouldBe -7.5
    }
}

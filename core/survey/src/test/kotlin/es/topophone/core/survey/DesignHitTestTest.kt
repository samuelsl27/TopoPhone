package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadEntityRef
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El elemento del dibujo más cercano a un toque (S040/RF-29). */
class DesignHitTestTest {

    private fun candidate(index: Int, kind: CadEntityKind, vararg xy: Double): DesignCandidate {
        val x = DoubleArray(xy.size / 2) { xy[it * 2] }
        val y = DoubleArray(xy.size / 2) { xy[it * 2 + 1] }
        return DesignCandidate(CadEntityRef("capa", 1L, index), CadEntity(kind, x, y, null, null))
    }

    private fun point(index: Int, x: Double, y: Double) = candidate(index, CadEntityKind.POINT, x, y)

    private fun line(index: Int, vararg xy: Double) = candidate(index, CadEntityKind.LINE, *xy)

    @Test
    fun `S040 RF-29 un punto dentro de la tolerancia se encuentra con su distancia`() {
        val hit = DesignHitTest.nearest(440000.0, 4474000.0, 0.6, listOf(point(0, 440000.3, 4474000.4)))!!

        hit.ref.index shouldBe 0
        hit.distance shouldBe (0.5 plusOrMinus 1e-6)
    }

    @Test
    fun `S040 RF-29 un punto fuera de la tolerancia no se encuentra`() {
        DesignHitTest.nearest(0.0, 0.0, 0.5, listOf(point(0, 0.6, 0.0))).shouldBeNull()
        DesignHitTest.nearest(0.0, 0.0, 0.5, emptyList()).shouldBeNull()
    }

    @Test
    fun `S040 RF-29 la tolerancia es inclusiva`() {
        DesignHitTest.nearest(0.0, 0.0, 0.5, listOf(point(0, 0.5, 0.0)))!!.ref.index shouldBe 0
    }

    @Test
    fun `S040 RF-29 en una linea cuenta la distancia al segmento y no solo a los vertices`() {
        val l = line(0, 0.0, 0.0, 10.0, 0.0)

        DesignHitTest.nearest(5.0, 0.3, 0.5, listOf(l))!!.distance shouldBe (0.3 plusOrMinus 1e-9)
        DesignHitTest.nearest(5.0, 0.6, 0.5, listOf(l)).shouldBeNull()
    }

    @Test
    fun `S040 RF-29 pasado el extremo del segmento cuenta la distancia al extremo`() {
        val l = line(0, 0.0, 0.0, 10.0, 0.0)

        DesignHitTest.nearest(10.3, 0.4, 0.6, listOf(l))!!.distance shouldBe (0.5 plusOrMinus 1e-9)
        DesignHitTest.nearest(10.6, 0.4, 0.6, listOf(l)).shouldBeNull()
    }

    @Test
    fun `S040 RF-29 en una polilinea gana el segmento mas cercano`() {
        val poly = candidate(0, CadEntityKind.POLYLINE, 0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)

        DesignHitTest.nearest(10.2, 5.0, 0.5, listOf(poly))!!.distance shouldBe (0.2 plusOrMinus 1e-9)
        DesignHitTest.nearest(5.0, 5.0, 0.5, listOf(poly)).shouldBeNull() // dentro del polígono pero lejos de los lados
    }

    @Test
    fun `S040 RF-29 entre varios elementos gana el mas cercano y en empate el primero`() {
        val far = line(0, 0.0, 1.0, 10.0, 1.0)
        val near = line(1, 0.0, 0.2, 10.0, 0.2)
        val tied = line(2, 0.0, -0.2, 10.0, -0.2)

        DesignHitTest.nearest(5.0, 0.0, 2.0, listOf(far, near))!!.ref.index shouldBe 1
        DesignHitTest.nearest(5.0, 0.0, 2.0, listOf(far, near, tied))!!.ref.index shouldBe 1
        DesignHitTest.nearest(5.0, 0.0, 2.0, listOf(tied, near))!!.ref.index shouldBe 2
    }

    @Test
    fun `S040 RF-29 un elemento de un solo vertice, como un circulo minimo, se toca como un punto`() {
        val tiny = candidate(0, CadEntityKind.CIRCLE, 3.0, 4.0)

        DesignHitTest.nearest(3.1, 4.0, 0.5, listOf(tiny))!!.distance shouldBe (0.1 plusOrMinus 1e-9)
    }

    @Test
    fun `S040 RF-29 un elemento lejano se descarta por su recuadro sin mirar sus segmentos`() {
        val big = candidate(0, CadEntityKind.POLYLINE, *DoubleArray(2000) { if (it % 2 == 0) 1000.0 + it else 1000.0 })

        DesignHitTest.nearest(0.0, 0.0, 1.0, listOf(big)).shouldBeNull()
    }

    @Test
    fun `S040 RF-29 con coordenadas UTM grandes la distancia sigue siendo exacta`() {
        val l = line(0, 440000.0, 4474000.0, 440100.0, 4474000.0)

        DesignHitTest.nearest(440050.0, 4474000.25, 1.0, listOf(l))!!.distance shouldBe (0.25 plusOrMinus 1e-6)
    }

    @Test
    fun `S040 RF-29 devuelve el elemento que se tocaba entre cien mil`() {
        val many = List(100_000) { line(it, it * 10.0, 0.0, it * 10.0 + 5.0, 0.0) }

        val hit = DesignHitTest.nearest(500_002.0, 0.1, 0.5, many)!!

        hit.ref.index shouldBe 50_000
    }
}

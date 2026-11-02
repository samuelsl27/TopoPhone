package es.topophone.app.ui.linear

import es.topophone.core.model.survey.linear.SurveyFlag
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** El modelo del gráfico de un perfil, su zoom y desplazamiento y desmonte o terraplén (S052/RF-13, RF-19). */
class ProfileChartTest {
    private fun point(x: Double, ground: Double, reference: Double? = null, inProfile: Boolean = true, flag: SurveyFlag = SurveyFlag.NONE) =
        ProfileChartPoint("P$x", x, ground, reference, reference?.let { ground - it }, inProfile, flag)

    private val chart = ProfileChart(
        ProfileAxis.CHAINAGE,
        listOf(point(100.0, 10.0, 9.0), point(200.0, 12.0, 12.0), point(300.0, 11.0, 12.5), point(400.0, 20.0, null, inProfile = false)),
        reference = listOf(100.0 to 9.0, 300.0 to 12.5),
    )

    @Test
    fun `S052 RF-13 por encima de la rasante es desmonte por debajo terraplen y a la misma cota nada`() {
        CutFill.of(0.245) shouldBe CutFill.CUT
        CutFill.of(-0.245) shouldBe CutFill.FILL
        CutFill.of(0.0) shouldBe CutFill.ON_REFERENCE
        CutFill.of(0.0004) shouldBe CutFill.ON_REFERENCE
        CutFill.of(-0.0004) shouldBe CutFill.ON_REFERENCE
        CutFill.of(0.0006) shouldBe CutFill.CUT
    }

    @Test
    fun `S052 RF-13 el dominio del grafico es el de los puntos del perfil y las marcas fuera del perfil no lo ensanchan`() {
        chart.domain() shouldBe (100.0 to 300.0)
        ProfileChart(ProfileAxis.OFFSET, emptyList(), emptyList()).domain().shouldBeNull()
    }

    @Test
    fun `S052 RF-13 con un solo punto el dominio tiene anchura para poder dibujarlo`() {
        val domain = ProfileChart(ProfileAxis.OFFSET, listOf(point(5.0, 1.0)), emptyList()).domain().shouldNotBeNull()

        (domain.second - domain.first) shouldBe (1.0 plusOrMinus 1e-12)
        ((domain.first + domain.second) / 2) shouldBe (5.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S052 RF-13 sin zoom se ve todo el dominio y cada zoom lo reparte entre el factor`() {
        val v = ProfileViewport()

        v.window(100.0, 300.0) shouldBe (100.0 to 300.0)
        val x2 = v.zoomIn().window(100.0, 300.0)
        (x2.second - x2.first) shouldBe (100.0 plusOrMinus 1e-9)
        ((x2.first + x2.second) / 2) shouldBe (200.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S052 RF-13 el zoom tiene un limite por abajo en 1 y por arriba y alejar vuelve a lo ancho`() {
        val v = ProfileViewport()

        v.zoomOut().zoom shouldBe 1.0
        var zoomed = v
        repeat(40) { zoomed = zoomed.zoomIn() }
        zoomed.zoom shouldBe ProfileViewport.MAX_ZOOM
        zoomed.zoomOut().zoomOut().zoom shouldBe (ProfileViewport.MAX_ZOOM / ProfileViewport.STEP / ProfileViewport.STEP plusOrMinus 1e-9)
        zoomed.reset() shouldBe ProfileViewport()
    }

    @Test
    fun `S052 RF-13 desplazar mueve la ventana sin salirse del dominio por ningun lado`() {
        val zoomed = ProfileViewport().zoomIn().zoomIn() // ventana de la cuarta parte

        val right = zoomed.panBy(0.5).window(0.0, 400.0)
        (right.second - right.first) shouldBe (100.0 plusOrMinus 1e-9)
        right.first shouldBe (200.0 plusOrMinus 1e-9)
        zoomed.panBy(10.0).window(0.0, 400.0).second shouldBe (400.0 plusOrMinus 1e-9)
        zoomed.panBy(-10.0).window(0.0, 400.0).first shouldBe (0.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S052 RF-13 el zoom por gesto mantiene bajo el dedo el punto que habia`() {
        val v = ProfileViewport().zoomBy(2.0, anchorFraction = 0.25)

        val w = v.window(0.0, 400.0)
        (w.first + 0.25 * (w.second - w.first)) shouldBe (100.0 plusOrMinus 1e-9)
    }

    @Test
    fun `S052 RF-13 las cotas visibles salen de los puntos y de la referencia dentro de la ventana con margen`() {
        val range = chart.heightRange(100.0 to 200.0)

        (range.first < 9.0) shouldBe true
        (range.second > 12.0) shouldBe true
        (range.second < 20.0) shouldBe true // el punto de x = 400 queda fuera de la ventana
    }

    @Test
    fun `S052 RF-13 una ventana sin cotas distintas se ensancha para no dibujar una linea de alto cero`() {
        val flat = ProfileChart(ProfileAxis.CHAINAGE, listOf(point(0.0, 5.0), point(1.0, 5.0)), emptyList())

        val range = flat.heightRange(0.0 to 1.0)

        (range.second - range.first >= 1.0) shouldBe true
    }

    @Test
    fun `S052 RF-13 el punto mas cercano a una posicion es el de menor distancia en x y se ignora el que esta fuera del perfil`() {
        chart.nearest(110.0) shouldBe 0
        chart.nearest(190.0) shouldBe 1
        chart.nearest(1000.0) shouldBe 2 // el punto de x = 400 no entra en el perfil
        ProfileChart(ProfileAxis.CHAINAGE, emptyList(), emptyList()).nearest(0.0).shouldBeNull()
    }
}

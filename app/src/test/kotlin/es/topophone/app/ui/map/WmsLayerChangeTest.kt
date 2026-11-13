package es.topophone.app.ui.map

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Qué hace el mapa cuando cambia el plan de las capas WMS: nada, solo la opacidad o rehacerlas (S036/RF-21, RF-22, RF-33). */
class WmsLayerChangeTest {

    private fun tiles(id: String, opacity: Float = 1f, template: String = "https://$id.es/wms?BBOX={bbox-epsg-3857}") =
        WmsPlanLayer(id, opacity, WmsLayerSource.Tiles(template), "© $id")

    private fun plan(vararg layers: WmsPlanLayer) = WmsLayerPlan(layers.toList())

    @Test
    fun `S036 RF-33 el mismo plan no toca el mapa`() {
        val plan = plan(tiles("a"), tiles("b"))

        WmsLayerChange.between(plan, plan(tiles("a"), tiles("b"))) shouldBe WmsLayerChange.None
    }

    @Test
    fun `S036 RF-21 cambiar solo la opacidad cambia solo esa propiedad de esa capa`() {
        val applied = plan(tiles("a", 1f), tiles("b", 0.5f), tiles("c", 1f))
        val desired = plan(tiles("a", 1f), tiles("b", 0.25f), tiles("c", 1f))

        WmsLayerChange.between(applied, desired) shouldBe WmsLayerChange.Opacity(listOf(tiles("b", 0.25f)))
    }

    @Test
    fun `S036 RF-22 reordenar rehace las capas`() {
        WmsLayerChange.between(plan(tiles("a"), tiles("b")), plan(tiles("b"), tiles("a"))) shouldBe WmsLayerChange.Rebuild
    }

    @Test
    fun `S036 RF-20 ocultar o anadir una capa rehace las capas`() {
        WmsLayerChange.between(plan(tiles("a"), tiles("b")), plan(tiles("a"))) shouldBe WmsLayerChange.Rebuild
        WmsLayerChange.between(plan(tiles("a")), plan(tiles("a"), tiles("b"))) shouldBe WmsLayerChange.Rebuild
        WmsLayerChange.between(WmsLayerPlan.EMPTY, plan(tiles("a"))) shouldBe WmsLayerChange.Rebuild
        WmsLayerChange.between(plan(tiles("a")), WmsLayerPlan.EMPTY) shouldBe WmsLayerChange.Rebuild
    }

    @Test
    fun `S036 RF-29 una capa que cambia de peticion rehace las capas`() {
        val applied = plan(tiles("a", template = "https://a.es/wms?VERSION=1.3.0&BBOX={bbox-epsg-3857}"))
        val desired = plan(tiles("a", template = "https://a.es/wms?VERSION=1.1.1&BBOX={bbox-epsg-3857}"))

        WmsLayerChange.between(applied, desired) shouldBe WmsLayerChange.Rebuild
    }

    @Test
    fun `S036 RF-30 cambiar solo la atribucion no toca el mapa`() {
        val applied = plan(WmsPlanLayer("a", 1f, WmsLayerSource.Tiles("t"), "© antes"))
        val desired = plan(WmsPlanLayer("a", 1f, WmsLayerSource.Tiles("t"), "© despues"))

        WmsLayerChange.between(applied, desired) shouldBe WmsLayerChange.None
    }

    @Test
    fun `S036 RF-33 sin capas WMS no hay nada que hacer`() {
        WmsLayerChange.between(WmsLayerPlan.EMPTY, WmsLayerPlan.EMPTY) shouldBe WmsLayerChange.None
    }
}

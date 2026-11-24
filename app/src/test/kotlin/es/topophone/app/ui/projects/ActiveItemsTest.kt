package es.topophone.app.ui.projects

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Lo que está activo en «Datos del proyecto»: el orden, el estado de cada elemento y la lista vacía (S059/RF-18, RF-19, RF-21). */
class ActiveItemsTest {

    private val everything = ActiveInputs(
        terrainFactor = 0.999_912,
        localizationActive = true,
        localizationRmsM = 0.014,
        baseShiftName = "Vértice 4",
        surfaceName = "Terreno 2024",
        alignmentName = "Eje A-1",
        layersVisible = 2,
        layersTotal = 3,
    )

    @Test
    fun `S059 RF-18 los elementos activos salen en el orden terreno, localizacion, base shift, superficie, eje y capas`() {
        ActiveItems.of(everything).map { it.kind } shouldBe listOf(
            ActiveKind.TERRAIN,
            ActiveKind.LOCALIZATION,
            ActiveKind.BASE_SHIFT,
            ActiveKind.SURFACE,
            ActiveKind.ALIGNMENT,
            ActiveKind.LAYERS,
        )
    }

    @Test
    fun `S059 RF-19 cada elemento lleva su estado`() {
        val items = ActiveItems.of(everything).associate { it.kind to it.status }

        items[ActiveKind.TERRAIN] shouldBe ActiveStatus.Factor(0.999_912)
        items[ActiveKind.LOCALIZATION] shouldBe ActiveStatus.Rms(0.014)
        items[ActiveKind.BASE_SHIFT] shouldBe ActiveStatus.Name("Vértice 4")
        items[ActiveKind.SURFACE] shouldBe ActiveStatus.Name("Terreno 2024")
        items[ActiveKind.ALIGNMENT] shouldBe ActiveStatus.Name("Eje A-1")
        items[ActiveKind.LAYERS] shouldBe ActiveStatus.Count(2, 3)
    }

    @Test
    fun `S059 RF-19 una localizacion sin RMS lo dice con un estado sin cifra`() {
        val items = ActiveItems.of(ActiveInputs(localizationActive = true))

        items.single().status shouldBe ActiveStatus.Rms(null)
    }

    @Test
    fun `S059 RF-18 solo salen los elementos que estan activos`() {
        val items = ActiveItems.of(ActiveInputs(baseShiftName = "Vértice 4", layersVisible = 1, layersTotal = 1))

        items.map { it.kind } shouldBe listOf(ActiveKind.BASE_SHIFT, ActiveKind.LAYERS)
    }

    @Test
    fun `S059 RF-21 sin nada activo la lista queda vacia`() {
        ActiveItems.of(ActiveInputs()) shouldBe emptyList()
    }

    @Test
    fun `S059 RF-21 el mapa base no cuenta como capa del proyecto`() {
        ActiveItems.of(ActiveInputs(layersVisible = 0, layersTotal = 0)) shouldBe emptyList()
    }

    @Test
    fun `S059 RF-19 un proyecto con capas todas ocultas las muestra con cero visibles`() {
        ActiveItems.of(ActiveInputs(layersVisible = 0, layersTotal = 2)).single().status shouldBe ActiveStatus.Count(0, 2)
    }
}

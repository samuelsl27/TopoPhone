package es.topophone.core.model.wms

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WmsModelTest {

    private fun layer(name: String?, code: String?) = WmsLayerInfo(
        ordinal = 0,
        depth = 0,
        name = name,
        title = "t",
        abstract = "",
        transparent = true,
        queryable = false,
        mercatorCode = code,
    )

    @Test
    fun `S036 RF-26 el limite es de 10 capas por proyecto`() {
        WmsLimits.MAX_PROJECT_LAYERS shouldBe 10
    }

    @Test
    fun `S036 RF-8 el tiempo de espera es de 20 s y el documento de capacidades de 20 MB`() {
        WmsLimits.TIMEOUT_S shouldBe 20
        WmsLimits.MAX_CAPABILITIES_BYTES shouldBe 20L * 1024 * 1024
    }

    @Test
    fun `S036 RF-11 las capas anidadas llegan a 10 niveles`() {
        WmsLimits.MAX_DEPTH shouldBe 10
    }

    @Test
    fun `S036 RF-21 la opacidad va de 0 a 100 de 5 en 5`() {
        WmsLimits.OPACITY_STEP shouldBe 5
        listOf(0, 5, 50, 95, 100).forEach { WmsLimits.isValidOpacity(it) shouldBe true }
        listOf(-5, 1, 3, 52, 101, 105).forEach { WmsLimits.isValidOpacity(it) shouldBe false }
    }

    @Test
    fun `S036 RF-21 un valor cualquiera se ajusta al paso de 5 mas cercano dentro del rango`() {
        WmsLimits.snapOpacity(52) shouldBe 50
        WmsLimits.snapOpacity(53) shouldBe 55
        WmsLimits.snapOpacity(-20) shouldBe 0
        WmsLimits.snapOpacity(240) shouldBe 100
        WmsLimits.snapOpacity(100) shouldBe 100
    }

    @Test
    fun `S036 RF-12 una capa se puede anadir si tiene nombre y codigo de Web Mercator`() {
        layer("ortofoto", "EPSG:3857").isAddable shouldBe true
        layer("ortofoto", "EPSG:3785").isAddable shouldBe true
        layer(null, "EPSG:3857").isAddable shouldBe false // solo agrupa
        layer("ortofoto", null).isAddable shouldBe false // no ofrece 3857
    }

    @Test
    fun `S036 RF-12 el motivo de que no se pueda anadir es el que falta`() {
        layer(null, "EPSG:3857").notAddableReason shouldBe WmsLayerProblem.GROUP_ONLY
        layer("a", null).notAddableReason shouldBe WmsLayerProblem.NO_WEB_MERCATOR
        layer(null, null).notAddableReason shouldBe WmsLayerProblem.GROUP_ONLY
        layer("a", "EPSG:3857").notAddableReason shouldBe null
    }

    @Test
    fun `S036 RF-9 los codigos que valen como Web Mercator son el 3857 y sus alias`() {
        listOf("EPSG:3857", "EPSG:900913", "EPSG:102100", "EPSG:102113", "EPSG:3785").forEach { WmsLimits.isWebMercator(it) shouldBe true }
        listOf("EPSG:4326", "EPSG:25830", "CRS:84", "", "3857").forEach { WmsLimits.isWebMercator(it) shouldBe false }
    }

    @Test
    fun `S036 RF-9 los codigos se reconocen sin distinguir mayusculas ni espacios`() {
        WmsLimits.isWebMercator(" epsg:3857 ") shouldBe true
        WmsLimits.isWebMercator("EPSG:3785") shouldBe true
    }

    @Test
    fun `S036 RF-9 de varios codigos se prefiere el 3857`() {
        WmsLimits.preferredWebMercator(listOf("EPSG:4326", "EPSG:102100", "EPSG:3857", "EPSG:900913")) shouldBe "EPSG:3857"
        WmsLimits.preferredWebMercator(listOf("EPSG:4326", "EPSG:3785", "EPSG:102113")) shouldBe "EPSG:3785"
        WmsLimits.preferredWebMercator(listOf("EPSG:4326", "EPSG:25830")) shouldBe null
        WmsLimits.preferredWebMercator(emptyList()) shouldBe null
    }

    @Test
    fun `S036 RF-21 una capa del proyecto no admite una opacidad fuera de la rejilla`() {
        shouldThrow<IllegalArgumentException> { ProjectMapLayer("1", "p", "s", "capa", 0, true, 53) }
        ProjectMapLayer("1", "p", "s", "capa", 0, true, 55).opacity shouldBe 55
    }

    @Test
    fun `S036 RF-13 un preset en una imagen no es un preset por teselas`() {
        val preset = WmsPreset(
            key = "catastro-wms",
            name = "Catastro",
            region = "España",
            group = "Catastro",
            year = null,
            address = "https://ovc.catastro.meh.es/Cartografia/WMS/ServidorWMS.aspx",
            attribution = "© Dirección General del Catastro",
            license = "Uso libre",
            licenseUrl = null,
            requestMode = WmsRequestMode.IMAGE,
            preferredFormat = null,
            infoVersion = null,
            suggestedLayers = listOf("Catastro"),
        )

        preset.requestMode shouldBe WmsRequestMode.IMAGE
        preset.suggestedLayers shouldBe listOf("Catastro")
    }
}

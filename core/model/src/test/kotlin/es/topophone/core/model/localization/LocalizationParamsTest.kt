package es.topophone.core.model.localization

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocalizationParamsTest {
    private val params = LocalizationParams(
        centreE = 441026.87187997246,
        centreN = 4472941.741090292,
        knownCentreE = 426377.5368403295,
        knownCentreN = 4472546.968408878,
        rotationRad = 0.0035,
        scale = 1.00002,
        hasHorizontal = true,
        verticalKind = VerticalKind.PLANE,
        dh = -7.372802866540638,
        slopeE = 3.0e-6,
        slopeN = -2.0e-6,
        verticalE = 441026.0,
        verticalN = 4472941.0,
    )

    @Test
    fun `S039 RF-4 y RF-13 los limites y valores por defecto son los de la spec`() {
        LocalizationLimits.MAX_PAIRS shouldBe 50
        LocalizationLimits.DEFAULT_RESIDUAL_LIMIT_M shouldBe 0.03
        LocalizationLimits.DEFAULT_SCALE_LIMIT shouldBe 0.001
    }

    @Test
    fun `S039 RF-10 la escala y la pendiente se dan en ppm y la traslacion es la del centroide`() {
        params.scalePpm shouldBe (20.0 plusOrMinus 1e-6)
        params.slopePpm shouldBe (3.605551275463989 plusOrMinus 1e-9)
        params.translationE shouldBe (params.knownCentreE - params.centreE)
        params.translationN shouldBe (params.knownCentreN - params.centreN)
    }

    @Test
    fun `S039 RF-13 el texto de los parametros se recupera sin redondear`() {
        LocalizationParams.parseOrNull(params.toText()) shouldBe params
        LocalizationParams.parseOrNull(LocalizationParams().toText()) shouldBe LocalizationParams()
    }

    @Test
    fun `S039 RF-13 un texto vacio o roto no es una localizacion`() {
        LocalizationParams.parseOrNull(null) shouldBe null
        LocalizationParams.parseOrNull("") shouldBe null
        LocalizationParams.parseOrNull("1;2;3") shouldBe null
        LocalizationParams.parseOrNull("a;b;c;d;e;f;g;h;i;j;k;l;m") shouldBe null
    }

    @Test
    fun `S039 RF-5 un par sin confirmar o sin componente activa no entra en el ajuste`() {
        val pair = LocalizationPair("p", "id", "P1", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        pair.countsHorizontal shouldBe true
        pair.countsVertical shouldBe true
        pair.copy(useHorizontal = false).countsHorizontal shouldBe false
        pair.copy(useVertical = false).countsVertical shouldBe false
        pair.copy(confirmed = false).countsHorizontal shouldBe false
        pair.copy(scH = null).countsVertical shouldBe false
        pair.copy(knownH = null).countsVertical shouldBe false
    }

    @Test
    fun `S039 RF-9 una localizacion vacia es la identidad`() {
        LocalizationParams().isIdentity shouldBe true
        params.isIdentity shouldBe false
    }
}

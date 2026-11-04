package es.topophone.app.ui.map

import es.topophone.core.model.codes.CodeStyle
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Los puntos llevan al mapa el color y el simbolo de su codigo (S024/RF-17, RF-19). */
class MapPointsCodeTest {

    private fun summary(
        id: String,
        code: String? = null,
        color: Int? = null,
        symbol: CodeSymbol? = null,
        origin: PointOrigin = PointOrigin.MEASURED,
        outOfTolerance: Boolean = false,
    ) = PointSummary(
        id, id, "", origin, 40.0, -3.0, true, outOfTolerance, false, false, 0,
        code = code, codeColor = color, codeSymbol = symbol,
    )

    @Test
    fun `S024 RF-17 un punto con codigo se dibuja con el color y el simbolo vigentes de su codigo`() {
        val styles = mapOf("poste" to CodeStyle(0xD32F2F, CodeSymbol.TRIANGLE))

        val point = MapPoints.from(listOf(summary("1", "Poste", 0x111111, CodeSymbol.CIRCLE)), styles).single()

        point.color shouldBe 0xD32F2F
        point.symbol shouldBe CodeSymbol.TRIANGLE
    }

    @Test
    fun `S024 RF-19 cambiar el color o el simbolo del codigo cambia los puntos de ese codigo y no los demas`() {
        val points = listOf(summary("1", "ARB", 0x111111), summary("2", "POS", 0x222222), summary("3"))
        val before = MapPoints.from(
            points,
            mapOf(
                "arb" to CodeStyle(0x111111, CodeSymbol.CIRCLE),
                "pos" to CodeStyle(0x222222, CodeSymbol.CIRCLE),
            ),
        )
        val after = MapPoints.from(
            points,
            mapOf(
                "arb" to CodeStyle(0x00FF00, CodeSymbol.STAR),
                "pos" to CodeStyle(0x222222, CodeSymbol.CIRCLE),
            ),
        )

        before.first { it.id == "1" }.color shouldBe 0x111111
        after.first { it.id == "1" }.color shouldBe 0x00FF00
        after.first { it.id == "1" }.symbol shouldBe CodeSymbol.STAR
        after.first { it.id == "2" } shouldBe before.first { it.id == "2" }
    }

    @Test
    fun `S024 RF-17 si el codigo ya no esta entre los del proyecto se usan el color y el simbolo que guardo el punto`() {
        val point = MapPoints.from(listOf(summary("1", "ARB", 0x689F38, CodeSymbol.DIAMOND)), emptyMap()).single()

        point.color shouldBe 0x689F38
        point.symbol shouldBe CodeSymbol.DIAMOND
    }

    @Test
    fun `S024 RF-17 la clave del codigo no distingue mayusculas ni acentos`() {
        val point = MapPoints.from(listOf(summary("1", "Árbol")), mapOf("arbol" to CodeStyle(0x123456, CodeSymbol.CROSS))).single()

        point.color shouldBe 0x123456
        point.symbol shouldBe CodeSymbol.CROSS
    }

    @Test
    fun `S024 RF-17 un punto sin codigo no lleva color y es un circulo`() {
        val point = MapPoints.from(listOf(summary("1"))).single()

        point.color.shouldBeNull()
        point.symbol shouldBe CodeSymbol.CIRCLE
    }

    @Test
    fun `S024 RF-17 fuera de tolerancia y replanteado conservan su marca ademas del codigo`() {
        val styles = mapOf("arb" to CodeStyle(0x123456, CodeSymbol.SQUARE))

        val points = MapPoints.from(
            listOf(summary("out", "ARB", outOfTolerance = true), summary("staked", "ARB", origin = PointOrigin.STAKED)),
            styles,
        ).associateBy { it.id }

        points.getValue("out").outOfTolerance shouldBe true
        points.getValue("staked").staked shouldBe true
        points.getValue("out").color shouldBe 0x123456
    }
}

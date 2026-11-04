package es.topophone.app.ui.map

import es.topophone.core.model.codes.CodeSymbol
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test

/** Las formas con que el mapa dibuja los puntos de cada codigo (S024/RF-17). */
class PointsSymbolLayerTest {

    @Test
    fun `S024 RF-17 cada simbolo no circular tiene una forma dentro del cuadro unidad`() {
        CodeSymbol.entries.filter { it != CodeSymbol.CIRCLE }.forEach { symbol ->
            val polygon = SymbolShapes.polygon(symbol)
            (polygon.size >= 3) shouldBe true
            polygon.forEach { (x, y) ->
                (x in 0f..1f) shouldBe true
                (y in 0f..1f) shouldBe true
            }
        }
    }

    @Test
    fun `S024 RF-17 las formas son las de su nombre`() {
        SymbolShapes.polygon(CodeSymbol.SQUARE) shouldHaveSize 4
        SymbolShapes.polygon(CodeSymbol.TRIANGLE) shouldHaveSize 3
        SymbolShapes.polygon(CodeSymbol.DIAMOND) shouldHaveSize 4
        SymbolShapes.polygon(CodeSymbol.CROSS) shouldHaveSize 12
        SymbolShapes.polygon(CodeSymbol.STAR) shouldHaveSize 10
    }

    @Test
    fun `S024 RF-17 las formas se distinguen entre si`() {
        val shapes = CodeSymbol.entries.filter { it != CodeSymbol.CIRCLE }.map { SymbolShapes.polygon(it) }
        shapes.toSet().size shouldBe shapes.size
        SymbolShapes.polygon(CodeSymbol.SQUARE) shouldNotBe SymbolShapes.polygon(CodeSymbol.DIAMOND)
    }

    @Test
    fun `S024 RF-17 el circulo no tiene forma propia porque lo dibuja la capa de circulos`() {
        SymbolShapes.polygon(CodeSymbol.CIRCLE) shouldBe emptyList()
    }

    @Test
    fun `S024 RF-17 el icono depende del simbolo y del color efectivo`() {
        SymbolShapes.iconId(CodeSymbol.STAR, 0x1E88E5) shouldBe "topophone-code-STAR-1E88E5"
        SymbolShapes.iconId(CodeSymbol.STAR, 0x1E88E5) shouldNotBe SymbolShapes.iconId(CodeSymbol.STAR, 0xD32F2F)
        SymbolShapes.iconId(CodeSymbol.STAR, 0x1E88E5) shouldNotBe SymbolShapes.iconId(CodeSymbol.CROSS, 0x1E88E5)
    }

    @Test
    fun `S024 RF-17 un punto fuera de tolerancia se dibuja con el color de su aviso`() {
        SymbolShapes.effectiveColor(0x1E88E5, outOfTolerance = true) shouldBe SymbolShapes.OUT_OF_TOLERANCE_RGB
        SymbolShapes.effectiveColor(0x1E88E5, outOfTolerance = false) shouldBe 0x1E88E5
        SymbolShapes.effectiveColor(null, outOfTolerance = false) shouldBe SymbolShapes.DEFAULT_RGB
    }
}

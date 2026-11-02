package es.topophone.core.survey

import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeRule
import es.topophone.core.model.alignment.slope.SlopeSide
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El punto de talud: tipo, posición y altura contra los casos calculados aparte con fracciones (S051/RF-5, RF-6, RF-7, RF-8). */
class SlopeCatchTest {
    private val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje C").elements)

    private fun edgeOf(c: SlopeCase): SlopeEdgeResult.Edge {
        val side = if (c.rightSide) SlopeSide.RIGHT else SlopeSide.LEFT
        val rule = SlopeRule(side, 0.0, 200.0, c.cutRatio, c.fillRatio)
        return SlopeEdgeResult.Edge(rule, c.chainage, c.edgeOffset, c.edgeHeight, geometry.pointAt(c.chainage, c.edgeOffset)!!)
    }

    private fun terrainOf(c: SlopeCase): TerrainAt = if (c.terrain == "TIN") {
        TerrainAt(emptyList(), geometry, null, SlopeFixtures.tin())
    } else {
        TerrainAt(listOf(SlopeFixtures.ground(c.terrain, 100.0), SlopeFixtures.ground(c.terrain, 110.0)), geometry, null, null)
    }

    private fun result(name: String): SlopeCatchResult {
        val c = SlopeFixtures.cases().first { it.name == name }
        return SlopeCatch.of(edgeOf(c), terrainOf(c), geometry)
    }

    @Test
    fun `S051 RF-5 el tipo la posicion la cota y la altura de cada caso coinciden con la referencia a 1 mm`() {
        var compared = 0
        for (c in SlopeFixtures.cases().filter { it.state == "found" }) {
            val found = result(c.name).shouldBeInstanceOf<SlopeCatchResult.Found>()

            found.kind shouldBe SlopeKind.valueOf(c.kind)
            found.distanceM shouldBe (c.t!! plusOrMinus 1e-6)
            found.offsetM shouldBe (c.offset!! plusOrMinus 1e-6)
            found.heightM shouldBe (c.height!! plusOrMinus 1e-6)
            found.slopeHeightM shouldBe (c.slopeHeight!! plusOrMinus 1e-6)
            found.point.easting shouldBe (c.easting!! plusOrMinus 1e-6)
            found.point.northing shouldBe (c.northing!! plusOrMinus 1e-6)
            compared++
        }
        compared shouldBe 5
    }

    @Test
    fun `S051 RF-5 una cabeza en desmonte y un pie en terraplen`() {
        result("desmonte-derecha").shouldBeInstanceOf<SlopeCatchResult.Found>().kind shouldBe SlopeKind.CUT
        result("terraplen-derecha").shouldBeInstanceOf<SlopeCatchResult.Found>().kind shouldBe SlopeKind.FILL
    }

    @Test
    fun `S051 RF-8 con el terreno a la cota del borde el punto de talud es el borde con altura cero`() {
        for (name in listOf("borde-derecha", "borde-tin")) {
            val at = result(name).shouldBeInstanceOf<SlopeCatchResult.AtEdge>()
            val c = SlopeFixtures.cases().first { it.name == name }

            at.point.easting shouldBe (c.easting!! plusOrMinus 1e-9)
            at.heightM shouldBe (c.edgeHeight plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S051 RF-7 un terreno que llega a 100 m sin cortar al talud no tiene interseccion`() {
        result("sin-interseccion") shouldBe SlopeCatchResult.NoIntersection
    }

    @Test
    fun `S051 RF-6 un terreno que se acaba antes de cortar al talud no tiene terreno`() {
        result("sin-terreno-perfil") shouldBe SlopeCatchResult.NoTerrain
        result("sin-terreno-tin") shouldBe SlopeCatchResult.NoTerrain
    }

    @Test
    fun `S051 RF-6 sin terreno en el borde no se calcula`() {
        val c = SlopeFixtures.cases().first { it.name == "desmonte-derecha" }

        SlopeCatch.of(edgeOf(c), TerrainAt(emptyList(), geometry, null, null), geometry) shouldBe SlopeCatchResult.NoTerrain
    }

    @Test
    fun `S051 RF-5 el tipo se decide en cada PK por el terreno de ese PK`() {
        val c = SlopeFixtures.cases().first { it.name == "desmonte-derecha" }
        // El mismo borde con un terreno 3 m mas bajo en otro PK: cambia de desmonte a terraplen.
        val low =
            TerrainAt(
                listOf(SlopeFixtures.ground("desmonte", 100.0, -3.0), SlopeFixtures.ground("desmonte", 110.0, -3.0)),
                geometry,
                null,
                null,
            )

        SlopeCatch.of(edgeOf(c), low, geometry).shouldBeInstanceOf<SlopeCatchResult.Found>().kind shouldBe SlopeKind.FILL
    }
}

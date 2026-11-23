package es.topophone.core.survey

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Importar en un proyecto en terreno: «las coordenadas del proyecto» son de terreno y quedan en cuadrícula; un SC declarado no cambia (S058/RF-17). */
class TerrainImportTest {
    private val crs = SpainCrs.ETRS89_UTM30
    private val view = TerrainView.of(TerrainPlane(40.4, -3.7, 1.0004), crs).shouldNotBeNull()
    private val entity = CadEntity(CadEntityKind.POINT, doubleArrayOf(440_300.12), doubleArrayOf(4_474_250.10), doubleArrayOf(650.0), null)

    private fun converted(projector: CadProjector): CadEntity =
        projector.convert(entity).shouldBeInstanceOf<CadProjection.Converted>().entity

    @Test
    fun `S058 RF-17 un DXF del proyecto en terreno queda en cuadricula`() {
        val out = converted(CadProjector(crs, CadImportOptions(), terrain = view))

        val grid = view.toGrid(440_300.12, 4_474_250.10)
        out.easting[0] shouldBe (grid.easting plusOrMinus 1e-9)
        out.northing[0] shouldBe (grid.northing plusOrMinus 1e-9)
        out.height!![0] shouldBe 650.0
    }

    @Test
    fun `S058 RF-17 el mismo DXF en un proyecto en cuadricula se guarda tal cual`() {
        val out = converted(CadProjector(crs, CadImportOptions()))

        out.easting[0] shouldBe (440_300.12 plusOrMinus 1e-9)
        out.northing[0] shouldBe (4_474_250.10 plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-17 un DXF con SC declarado no cambia por el terreno`() {
        val declared = CadImportOptions(source = CadSourceCrs.Utm(crs.datum, 29))

        val withTerrain = converted(CadProjector(crs, declared, terrain = view))
        val without = converted(CadProjector(crs, declared))

        withTerrain.easting[0] shouldBe (without.easting[0] plusOrMinus 1e-9)
        withTerrain.northing[0] shouldBe (without.northing[0] plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-17 una superficie del proyecto en terreno queda en cuadricula y la cota no cambia`() {
        val out = SurfaceProjector(
            crs,
            terrain = view,
        ).convert(doubleArrayOf(440_300.12), doubleArrayOf(4_474_250.10), doubleArrayOf(650.0))

        val converted = out.shouldBeInstanceOf<SurfaceProjection.Converted>()
        val grid = view.toGrid(440_300.12, 4_474_250.10)
        converted.easting[0] shouldBe (grid.easting plusOrMinus 1e-9)
        converted.northing[0] shouldBe (grid.northing plusOrMinus 1e-9)
        converted.height[0] shouldBe 650.0
    }
}

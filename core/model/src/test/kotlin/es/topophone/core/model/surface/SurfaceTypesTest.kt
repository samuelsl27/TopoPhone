package es.topophone.core.model.surface

import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos de una superficie y lo que guarda un replanteo contra ella (S042/RF-14, RF-22, RF-23, RF-28, RF-29). */
class SurfaceTypesTest {

    private val design = PointCoordinates(GeodeticPosition(40.4168, -3.7038, 699.423), 440290.45805, 4474257.38189, 648.19)

    private fun ref() = StakeoutRef("design-1", "12", design, -0.0001, -0.0199, 0.0199, -0.005)

    @Test
    fun `S042 RF-14 una superficie guarda su origen su unidad su extension y sus recuentos`() {
        val extent = SurfaceExtent(440290.0, 440410.0, 4474256.0, 4474356.0)
        val surface = Surface(
            "s1", "p1", "terreno", "obra.xml", 1_000L, CadSourceCrs.Project, LinearUnit.INTERNATIONAL_FOOT, extent, 600.0, 608.7, 42, 60, 2,
        )

        surface.unit shouldBe LinearUnit.INTERNATIONAL_FOOT
        surface.holeCount shouldBe 2
        surface.extent.centerEasting shouldBe 440350.0
        surface.extent.centerNorthing shouldBe 4474306.0
        surface shouldBe surface.copy()
    }

    @Test
    fun `S042 RF-22 el recuadro contiene sus bordes y no lo de fuera`() {
        val extent = SurfaceExtent(0.0, 10.0, 0.0, 20.0)

        extent.contains(0.0, 0.0) shouldBe true
        extent.contains(10.0, 20.0) shouldBe true
        extent.contains(10.001, 5.0) shouldBe false
        extent.contains(5.0, -0.001) shouldBe false
    }

    @Test
    fun `S042 RF-23 la cota fuera o cargando no tiene valor y el desplazamiento no la inventa`() {
        SurfaceHeight.Value(601.25).metresOrNull shouldBe 601.25
        SurfaceHeight.Outside.metresOrNull.shouldBeNull()
        SurfaceHeight.Loading.metresOrNull.shouldBeNull()
        SurfaceHeight.Outside.withOffset(0.3) shouldBe SurfaceHeight.Outside
        SurfaceHeight.Loading.withOffset(0.3) shouldBe SurfaceHeight.Loading
    }

    @Test
    fun `S042 RF-24 el desplazamiento vertical se suma a la cota de la superficie con signo`() {
        SurfaceHeight.Value(601.25).withOffset(0.3) shouldBe SurfaceHeight.Value(601.55)
        SurfaceHeight.Value(601.25).withOffset(-0.5) shouldBe SurfaceHeight.Value(600.75)
        SurfaceHeight.Value(601.25).withOffset(0.0) shouldBe SurfaceHeight.Value(601.25)
    }

    @Test
    fun `S042 RF-28 un replanteo de antes no tiene origen de la cota ni datos de superficie`() {
        val plain = ref()

        plain.elevationSource.shouldBeNull()
        plain.surfaceName.shouldBeNull()
        plain.surfaceImportedAtMs.shouldBeNull()
        plain.surfaceElevationM.shouldBeNull()
        plain.verticalOffsetM.shouldBeNull()
        plain.usedSurface shouldBe false
    }

    @Test
    fun `S042 RF-28 el replanteo contra la superficie guarda su nombre su fecha su cota y el desplazamiento`() {
        val staked = ref().copy(
            designElevationM = 601.55,
            elevationSource = ElevationSource.SURFACE,
            surfaceName = "terreno",
            surfaceImportedAtMs = 1_000L,
            surfaceElevationM = 601.25,
            verticalOffsetM = 0.3,
        )

        staked.usedSurface shouldBe true
        staked.surfaceName shouldBe "terreno"
        staked.surfaceElevationM shouldBe 601.25
        staked.verticalOffsetM shouldBe 0.3
        staked shouldBe staked.copy()
    }

    @Test
    fun `S042 RF-29 un replanteo con la cota del punto no usa la superficie`() {
        ref().copy(elevationSource = ElevationSource.POINT).usedSurface shouldBe false
    }
}

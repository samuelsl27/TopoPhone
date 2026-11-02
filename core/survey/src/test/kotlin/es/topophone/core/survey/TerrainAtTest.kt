package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceHeightSource
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** La cota del terreno en un PK y un desplazamiento: perfiles de terreno de S050 y luego la superficie (S051/RF-6). */
class TerrainAtTest {
    private val axisC = AlignmentGeometry(AlignmentFixtures.alignment("Eje C").elements)
    private val axisA = AlignmentGeometry(AlignmentFixtures.alignment("Eje A").elements)
    private val profileA = AlignmentFixtures.profileOf("Eje A")?.let { VerticalProfileEval(it) }

    private fun terrain(grounds: List<CrossSection> = emptyList(), surface: SurfaceHeightSource? = null) =
        TerrainAt(grounds, axisC, null, surface)

    @Test
    fun `S051 RF-6 en el PK de un perfil de terreno la cota es la del perfil interpolada en el desplazamiento`() {
        val t = terrain(listOf(SlopeFixtures.ground("desmonte", 100.0), SlopeFixtures.ground("desmonte", 110.0, lift = 1.0)))

        val sample = t.heightAt(100.0, 5.75).shouldBeInstanceOf<TerrainSample>() // a medio camino entre (3,5; 100,5) y (8; 102)

        sample.heightM shouldBe (101.25 plusOrMinus 1e-12)
        sample.source shouldBe TerrainSource.PROFILE
    }

    @Test
    fun `S051 RF-6 entre dos perfiles de terreno se interpola con el PK`() {
        val t = terrain(listOf(SlopeFixtures.ground("desmonte", 100.0), SlopeFixtures.ground("desmonte", 110.0, lift = 1.0)))

        t.heightAt(105.0, 5.75)!!.heightM shouldBe (101.75 plusOrMinus 1e-12)
        t.heightAt(102.5, 5.75)!!.heightM shouldBe (101.5 plusOrMinus 1e-12)
    }

    @Test
    fun `S051 RF-6 un perfil de terreno relativo suma la rasante del PK`() {
        val relative =
            CrossSection(SectionKind.GROUND, 100.0, HeightKind.RELATIVE, listOf(SectionPoint("T", 0.0, -1.0), SectionPoint("T", 10.0, 2.0)))
        val t = TerrainAt(listOf(relative), axisA, profileA, null)

        t.heightAt(100.0, 5.0)!!.heightM shouldBe (profileA!!.at(100.0)!!.elevation + 0.5 plusOrMinus 1e-9)
    }

    @Test
    fun `S051 RF-6 mas alla del primer y el ultimo perfil o del ancho del perfil pasa a la superficie`() {
        val t = terrain(listOf(SlopeFixtures.ground("desmonte", 100.0), SlopeFixtures.ground("desmonte", 110.0)), SlopeFixtures.tin())

        t.heightAt(95.0, 5.0)!!.source shouldBe TerrainSource.SURFACE
        t.heightAt(105.0, 150.0).shouldBeNull() // fuera del perfil y de la superficie
        t.heightAt(105.0, 5.0)!!.source shouldBe TerrainSource.PROFILE
    }

    @Test
    fun `S051 RF-6 sin perfiles la cota es la de la superficie sin su desplazamiento vertical`() {
        val c = SlopeFixtures.cases().first { it.name == "desmonte-tin" }
        val sample = terrain(surface = SlopeFixtures.tin()).heightAt(c.chainage, c.offset!!).shouldBeInstanceOf<TerrainSample>()

        sample.heightM shouldBe (c.height!! plusOrMinus 1e-9)
        sample.source shouldBe TerrainSource.SURFACE
    }

    @Test
    fun `S051 RF-6 una superficie sin cota en esa posicion o que carga no da terreno`() {
        terrain(surface = SurfaceHeightSource { _, _ -> SurfaceHeight.Outside }).heightAt(100.0, 5.0).shouldBeNull()
        terrain(surface = SurfaceHeightSource { _, _ -> SurfaceHeight.Loading }).heightAt(100.0, 5.0).shouldBeNull()
    }

    @Test
    fun `S051 RF-6 sin ninguna fuente no hay terreno`() {
        terrain().heightAt(100.0, 5.0).shouldBeNull()
    }

    @Test
    fun `S051 RF-6 un PK fuera del eje no tiene terreno`() {
        terrain(surface = SlopeFixtures.tin()).heightAt(9_999.0, 5.0).shouldBeNull()
    }
}

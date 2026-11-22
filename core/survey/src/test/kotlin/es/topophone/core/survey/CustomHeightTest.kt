package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GeoidSource
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** La altura de un SC personalizado: elipsoidal, un geoide instalado o una ondulación constante (S035/RF-5). */
class CustomHeightTest {
    private val definition = (
        CrsCatalog.bundled.build(
            25830,
            CorrectionsDatum.ETRS89,
            GeoPoint(40.4, -3.7),
        ) as CrsBuildResult.Ready
        ).definition
    private val custom = definition.copy(epsg = 0, name = "Obra")
    private val ground = GeodeticPosition(40.4168, -3.7038, 700.0)
    private val calculator = ProjectPositionCalculator()

    private fun crs(height: CustomHeight) = CustomCrs("c", "Obra", custom, height).toProjectCrs(CorrectionsDatum.ETRS89)

    @Test
    fun `S035 RF-5 con ondulacion constante la altitud es la elipsoidal menos esa ondulacion`() {
        val position = calculator.fromGround(ground, crs(CustomHeight.ConstantGeoid(48.5)), HeightSystem.ORTHOMETRIC)

        position.height.shouldNotBeNull() shouldBe (651.5 plusOrMinus 1e-9)
        position.unavailable shouldBe emptySet()
    }

    @Test
    fun `S035 RF-5 la ondulacion constante puede ser negativa`() {
        val position = calculator.fromGround(ground, crs(CustomHeight.ConstantGeoid(-12.25)), HeightSystem.ORTHOMETRIC)

        position.height.shouldNotBeNull() shouldBe (712.25 plusOrMinus 1e-9)
    }

    @Test
    fun `S035 RF-5 con altura elipsoidal no se usa ninguna ondulacion`() {
        val position = calculator.fromGround(ground, crs(CustomHeight.ConstantGeoid(48.5)), HeightSystem.ELLIPSOIDAL)

        position.height shouldBe 700.0
    }

    @Test
    fun `S035 RF-5 sin geoide ni ondulacion la altitud no esta disponible`() {
        val position = calculator.fromGround(ground, crs(CustomHeight.Ellipsoidal), HeightSystem.ORTHOMETRIC)

        position.height.shouldBeNull()
        position.unavailable.contains(PositionUnavailable.OUTSIDE_GEOID) shouldBe true
    }

    @Test
    fun `S035 RF-5 con un geoide instalado se usa su ondulacion`() {
        val resources = object : LibraryResources {
            override val grids = GridSource { null }
            override val generation = 0

            override fun geoid(gridName: String): GeoidSource? = object : GeoidSource {
                override val name = gridName
                override val attribution = ""

                override fun undulationM(latitudeDeg: Double, longitudeDeg: Double) = 50.0
            }
        }
        val engine = LibraryEngine(crs(CustomHeight.Geoid("egm.tif")), resources)

        engine.undulation(40.4, -3.7).shouldBeInstanceOf<Undulation.Value>().metres shouldBe 50.0
    }

    @Test
    fun `S035 RF-5 el motor da la ondulacion constante en cualquier punto y el geoide de la app no interviene`() {
        val engine = LibraryEngine(crs(CustomHeight.ConstantGeoid(3.0)), BundledLibraryResources)

        engine.undulation(10.0, 100.0) shouldBe Undulation.Value(3.0)
        engine.undulation(40.4, -3.7) shouldBe Undulation.Value(3.0)
    }

    @Test
    fun `S035 RF-5 las cotas de un raster se convierten con una ondulacion constante`() {
        SourceHeights.conversion(
            crs(CustomHeight.ConstantGeoid(3.0)),
            HeightSystem.ELLIPSOIDAL,
            HeightSystem.ORTHOMETRIC,
        ) shouldBe HeightConversion.Available
        SourceHeights.conversion(crs(CustomHeight.Ellipsoidal), HeightSystem.ELLIPSOIDAL, HeightSystem.ORTHOMETRIC) shouldBe
            HeightConversion.NoProjectGeoid
    }

    @Test
    fun `S035 RF-5 un SC de la biblioteca sin ondulacion no cambia`() {
        val plain = LibraryCrs(definition, CorrectionsDatum.ETRS89, "egm")

        plain.geoidConstantM.shouldBeNull()
    }
}

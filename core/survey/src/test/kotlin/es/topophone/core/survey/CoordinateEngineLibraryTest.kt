package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GeoidSource
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** El motor de un SC de la biblioteca frente al de España y con lo que falta (S034/RF-18, RF-24, RF-26, RF-27, RF-28). */
class CoordinateEngineLibraryTest {
    private val catalog = CrsCatalog.bundled
    private val calculator = ProjectPositionCalculator()
    private val madrid = GeodeticPosition(40.4168, -3.7038, 667.0)

    private fun library(epsg: Int, corrections: CorrectionsDatum = CorrectionsDatum.ETRS89, geoid: String? = null): LibraryCrs {
        val ready = catalog.build(epsg, corrections, GeoPoint(40.4, -3.7))
        val definition = when (ready) {
            is CrsBuildResult.Ready -> ready.definition
            is CrsBuildResult.NeedsGrids -> ready.definition
            else -> error("No se puede elegir EPSG:$epsg: $ready")
        }
        return LibraryCrs(definition, corrections, geoid)
    }

    @Test
    fun `S034 RF-26 ETRS89 UTM 30 de la biblioteca da lo mismo que el de Espana`() {
        val spain = calculator.fromGround(madrid, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
        val viaLibrary = calculator.fromGround(madrid, library(25830), HeightSystem.ELLIPSOIDAL)

        viaLibrary.easting.shouldNotBeNull() shouldBe spain.easting.shouldNotBeNull().plusOrMinus(1e-6)
        viaLibrary.northing.shouldNotBeNull() shouldBe spain.northing.shouldNotBeNull().plusOrMinus(1e-6)
        viaLibrary.scaleFactor.shouldNotBeNull() shouldBe spain.scaleFactor.shouldNotBeNull().plusOrMinus(1e-12)
        viaLibrary.convergenceRad.shouldNotBeNull() shouldBe spain.convergenceRad.shouldNotBeNull().plusOrMinus(1e-12)
        viaLibrary.combinedFactor.shouldNotBeNull() shouldBe spain.combinedFactor.shouldNotBeNull().plusOrMinus(1e-12)
        viaLibrary.height shouldBe 667.0
    }

    @Test
    fun `S034 RF-26 ED50 UTM 30 de la biblioteca con la rejilla del IGN da lo mismo que el de Espana a 0,1 mm`() {
        val spain = calculator.fromGround(madrid, SpainCrs.ED50_UTM30, HeightSystem.ELLIPSOIDAL)
        val viaLibrary = calculator.fromGround(madrid, library(23030), HeightSystem.ELLIPSOIDAL)

        abs(viaLibrary.easting.shouldNotBeNull() - spain.easting.shouldNotBeNull()) shouldBe (0.0).plusOrMinus(1e-4)
        abs(viaLibrary.northing.shouldNotBeNull() - spain.northing.shouldNotBeNull()) shouldBe (0.0).plusOrMinus(1e-4)
    }

    @Test
    fun `S034 RF-26 un SC geografico da las coordenadas del datum del SC y sin factores`() {
        val position = calculator.fromGround(madrid, library(4258), HeightSystem.ELLIPSOIDAL)

        position.easting.shouldBeNull()
        position.latitudeDeg.shouldNotBeNull() shouldBe madrid.latitudeDeg.plusOrMinus(1e-9)
        position.longitudeDeg.shouldNotBeNull() shouldBe madrid.longitudeDeg.plusOrMinus(1e-9)
        position.scaleFactor.shouldBeNull()
    }

    @Test
    fun `S034 RF-27 la altitud usa el geoide del proyecto, y sin instalar sale como no disponible con su nombre`() {
        val withGeoid = library(25830, geoid = "es_ign_egm08-rednap.tif")
        val spain = calculator.fromGround(madrid, SpainCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC)
        val position = calculator.fromGround(madrid, withGeoid, HeightSystem.ORTHOMETRIC)

        position.height.shouldNotBeNull() shouldBe spain.height.shouldNotBeNull().plusOrMinus(1e-9)

        val missing = calculator.fromGround(madrid, library(25830, geoid = "us_nga_egm08_25.tif"), HeightSystem.ORTHOMETRIC)
        missing.height.shouldBeNull()
        missing.unavailable shouldBe setOf(PositionUnavailable.MISSING_GRID)
        missing.missingGrid shouldBe "us_nga_egm08_25.tif"
        missing.easting.shouldNotBeNull() // las coordenadas no dependen del geoide (RF-18)
    }

    @Test
    fun `S034 RF-18 una rejilla del datum que falta deja las coordenadas no disponibles y dice cual`() {
        val noGrids = object : LibraryResources {
            override val grids = GridSource { null }
            override val generation = 1

            override fun geoid(gridName: String): GeoidSource? = null
        }
        val engine = LibraryEngine(library(23030), noGrids)

        val position = engine.fromGround(madrid, HeightSystem.ELLIPSOIDAL)

        position.easting.shouldBeNull()
        position.unavailable shouldBe setOf(PositionUnavailable.MISSING_GRID)
        position.missingGrid shouldBe "es_ign_SPED2ETV2.tif"
        engine.groundFromGrid(440000.0, 4474000.0).shouldBeInstanceOf<GroundResult.Unavailable>().missingGrid shouldBe
            "es_ign_SPED2ETV2.tif"
    }

    @Test
    fun `S034 RF-24 un SC sin rejillas descargadas trabaja con lo que trae la app`() {
        val engine = calculator.engineFor(library(25830))

        engine.fromGround(madrid, HeightSystem.ELLIPSOIDAL).easting.shouldNotBeNull()
        engine.groundFromGrid(440000.0, 4474000.0).shouldBeInstanceOf<GroundResult.Ok>()
    }

    @Test
    fun `S034 RF-26 del suelo al SC y de vuelta se recupera el punto en WGS 84 UTM 30 con correcciones ITRF`() {
        val crs = library(32630, CorrectionsDatum.ITRF_WGS84)
        val engine = calculator.engineFor(crs)
        val position = engine.fromGround(madrid, HeightSystem.ELLIPSOIDAL)

        val back = engine.groundFromGrid(position.easting.shouldNotBeNull(), position.northing.shouldNotBeNull())
            .shouldBeInstanceOf<GroundResult.Ok>()

        back.latitudeDeg shouldBe madrid.latitudeDeg.plusOrMinus(1e-10)
        back.longitudeDeg shouldBe madrid.longitudeDeg.plusOrMinus(1e-10)
    }

    @Test
    fun `S034 RF-26 fuera del ambito del SC no hay coordenadas`() {
        val position = calculator.fromGround(GeodeticPosition(-33.0, 151.0, 10.0), library(25830), HeightSystem.ELLIPSOIDAL)

        position.unavailable shouldBe setOf(PositionUnavailable.OUTSIDE_CRS_AREA)
        calculator.engineFor(library(25830)).containsPosition(-33.0, 151.0) shouldBe false
    }

    @Test
    fun `S034 RF-26 el motor se reutiliza mientras no cambie el SC ni las rejillas instaladas`() {
        val crs = library(25830)

        (LibraryEngines.of(crs, BundledLibraryResources) === LibraryEngines.of(crs, BundledLibraryResources)) shouldBe true
    }
}

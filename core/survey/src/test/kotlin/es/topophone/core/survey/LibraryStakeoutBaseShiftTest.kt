package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El replanteo y el desplazamiento de base con un SC de la biblioteca dan lo mismo que con el de España (S034/RF-28). */
class LibraryStakeoutBaseShiftTest {
    private val catalog = CrsCatalog.bundled
    private val calculator = ProjectPositionCalculator()
    private val manual = ManualCoordinates(calculator)

    private val library: LibraryCrs = when (val result = catalog.build(25830, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))) {
        is CrsBuildResult.Ready -> LibraryCrs(result.definition)
        else -> error("$result")
    }

    private fun design(crs: ProjectCrs): PointCoordinates = manual.compute(
        TypedCoordinates(EntryForm.PROJECT_CRS, 440000.0, 4474000.0, 650.0, HeightKind.ELLIPSOIDAL),
        crs,
        HeightSystem.ELLIPSOIDAL,
    ).shouldBeInstanceOf<ManualResult.Computed>().coordinates

    private fun tip(crs: ProjectCrs): PointCoordinates {
        val ground = GeodeticPosition(40.40395, -3.70105, 650.3)
        val position = calculator.fromGround(ground, crs, HeightSystem.ELLIPSOIDAL)
        return PointCoordinates(ground, position.easting, position.northing, position.height)
    }

    @Test
    fun `S034 RF-28 el replanteo da la misma guia con un SC de la biblioteca que con el de Espana`() {
        val spain = StakeoutGeometry.guidance(design(SpainCrs.ETRS89_UTM30), tip(SpainCrs.ETRS89_UTM30), 0.3)
        val viaLibrary = StakeoutGeometry.guidance(design(library), tip(library), 0.3)

        viaLibrary.distanceM shouldBe spain.distanceM.plusOrMinus(1e-6)
        viaLibrary.dEastM shouldBe spain.dEastM.plusOrMinus(1e-6)
        viaLibrary.dNorthM shouldBe spain.dNorthM.plusOrMinus(1e-6)
        (spain.distanceM > 1.0) shouldBe true
    }

    @Test
    fun `S034 RF-28 el desplazamiento de base es el mismo con un SC de la biblioteca`() {
        val known = KnownPoint(440000.0, 4474000.0, 650.0)
        val measured = GeodeticPosition(40.40001, -3.70001, 650.8)
        val spain = BaseShiftCalculator(manual, calculator).compute(known, measured, SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL, false)
            .shouldBeInstanceOf<BaseShiftResult.Computed>()
        val viaLibrary = BaseShiftCalculator(manual, calculator).compute(known, measured, library, HeightSystem.ELLIPSOIDAL, false)
            .shouldBeInstanceOf<BaseShiftResult.Computed>()

        viaLibrary.vector.dx shouldBe spain.vector.dx.plusOrMinus(1e-6)
        viaLibrary.vector.dy shouldBe spain.vector.dy.plusOrMinus(1e-6)
        viaLibrary.vector.dz shouldBe spain.vector.dz.plusOrMinus(1e-6)
        viaLibrary.deltaEastM shouldBe spain.deltaEastM.plusOrMinus(1e-6)
        viaLibrary.deltaNorthM shouldBe spain.deltaNorthM.plusOrMinus(1e-6)
    }

    @Test
    fun `S034 RF-28 una rejilla que falta rechaza el punto conocido con su motivo`() {
        val ed50Library = when (val result = catalog.build(23030, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))) {
            is CrsBuildResult.Ready -> LibraryCrs(result.definition)
            else -> error("$result")
        }
        val noGrids = object : LibraryResources {
            override val grids = es.topophone.core.geodesy.datum.GridSource { null }
            override val generation = 7

            override fun geoid(gridName: String) = null
        }
        val calculatorWithoutGrids = ProjectPositionCalculator(libraryResources = { noGrids })

        val result = BaseShiftCalculator(ManualCoordinates(calculatorWithoutGrids), calculatorWithoutGrids)
            .compute(
                KnownPoint(440000.0, 4474000.0, 650.0),
                GeodeticPosition(40.4, -3.7, 650.0),
                ed50Library,
                HeightSystem.ELLIPSOIDAL,
                false,
            )

        result shouldBe BaseShiftResult.Rejected(PositionUnavailable.MISSING_GRID)
        calculatorWithoutGrids.fromGround(
            GeodeticPosition(40.4, -3.7, 650.0),
            ed50Library,
            HeightSystem.ELLIPSOIDAL,
        ).missingGrid.shouldNotBeNull()
    }
}

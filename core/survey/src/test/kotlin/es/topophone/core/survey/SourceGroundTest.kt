package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CrsDefinitions
import es.topophone.core.geodesy.catalog.SourceCrsResolution
import es.topophone.core.geodesy.catalog.SourceCrsResolver
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * Del punto de un fichero en un SC de la biblioteca (otro país) al SC del proyecto frente a pyproj/PROJ (S057/RF-7, RF-11, RF-12; constitución nº 2:
 * ≤ 1 mm y la ida y vuelta ≤ 0,1 mm). Los casos son los de `fixtures/geodesia/reproyeccion.csv`: Lambert-93 (paso nulo), GGRS87 y Stereo 70
 * (parámetros) y ED50 UTM 30N (rejilla del IGN que va en la app).
 */
class SourceGroundTest {
    private val catalog = CrsCatalog.bundled

    private data class Vector(
        val origin: Int,
        val project: Int,
        val latitudeDeg: Double,
        val longitudeDeg: Double,
        val x: Double,
        val y: Double,
        val easting: Double,
        val northing: Double,
        val backX: Double,
        val backY: Double,
    )

    private val vectors = SurveyFixtures.rows("reproyeccion.csv").map {
        val n = it.drop(4).map(String::toDouble)
        Vector(it[0].toInt(), it[1].toInt(), n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7])
    }

    private fun corrections(projectEpsg: Int) = if (projectEpsg == 32634) CorrectionsDatum.ITRF_WGS84 else CorrectionsDatum.ETRS89

    private fun definition(epsg: Int, frame: CorrectionsDatum, at: GeoPoint): CrsDefinition =
        when (val r = catalog.build(epsg, frame, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            else -> error("EPSG:$epsg: $r")
        }

    private fun projectEngine(v: Vector): LibraryEngine {
        val frame = corrections(v.project)
        return LibraryEngine(
            LibraryCrs(definition(v.project, frame, GeoPoint(v.latitudeDeg, v.longitudeDeg)), frame),
            BundledLibraryResources,
        )
    }

    private fun toProject(v: Vector): Pair<Double, Double> {
        val source = SourceGround(definition(v.origin, corrections(v.project), GeoPoint(v.latitudeDeg, v.longitudeDeg)))
        val ground = source.toGround(v.x, v.y).shouldBeInstanceOf<GroundResult.Ok>()
        val position = projectEngine(
            v,
        ).fromGround(GeodeticPosition(ground.latitudeDeg, ground.longitudeDeg, null), HeightSystem.ELLIPSOIDAL)
        return position.easting!! to position.northing!!
    }

    @Test
    fun `S057 RF-7 del SC de origen al del proyecto da lo de pyproj a 1 mm en los cuatro casos`() {
        vectors.size shouldBe 12
        val failures = vectors.mapNotNull { v ->
            val (east, north) = toProject(v)
            val error = hypot(east - v.easting, north - v.northing)
            "EPSG:${v.origin} → ${v.project} (${v.latitudeDeg}, ${v.longitudeDeg}): $error m".takeIf { error > 0.001 }
        }
        failures.shouldBeEmpty()
    }

    @Test
    fun `S057 RF-7 de vuelta, del proyecto al origen, da lo de pyproj a 1 mm`() {
        val failures = vectors.mapNotNull { v ->
            val frame = corrections(v.project)
            val at = GeoPoint(v.latitudeDeg, v.longitudeDeg)
            val ground = projectEngine(v).groundFromGrid(v.easting, v.northing).shouldBeInstanceOf<GroundResult.Ok>()
            val source = LibraryEngine(LibraryCrs(definition(v.origin, frame, at), frame), BundledLibraryResources)
            val back = source.fromGround(GeodeticPosition(ground.latitudeDeg, ground.longitudeDeg, null), HeightSystem.ELLIPSOIDAL)
            val error = hypot(back.easting!! - v.backX, back.northing!! - v.backY)
            "EPSG:${v.origin} → ${v.project} (${v.latitudeDeg}, ${v.longitudeDeg}): $error m".takeIf { error > 0.001 }
        }
        failures.shouldBeEmpty()
    }

    @Test
    fun `S057 RF-3 las coordenadas en pies dan el mismo suelo que en metros`() {
        val v = vectors.first { it.origin == 3844 }
        val source = SourceGround(definition(v.origin, corrections(v.project), GeoPoint(v.latitudeDeg, v.longitudeDeg)))

        val metres = source.toGround(v.x, v.y).shouldBeInstanceOf<GroundResult.Ok>()
        val feet = source.toGround(v.x / 0.3048, v.y / 0.3048, 0.3048).shouldBeInstanceOf<GroundResult.Ok>()

        feet.latitudeDeg shouldBe (metres.latitudeDeg plusOrMinus 1e-11)
        feet.longitudeDeg shouldBe (metres.longitudeDeg plusOrMinus 1e-11)
    }

    @Test
    fun `S057 RF-7 un SC geografico salta la proyeccion y lleva el datum al de las correcciones`() {
        val source = SourceGround(definition(4326, CorrectionsDatum.ETRS89, GeoPoint(40.0, -3.0)))

        source.isProjected shouldBe false
        val ground = source.toGround(-3.5, 40.25).shouldBeInstanceOf<GroundResult.Ok>()

        ground.latitudeDeg shouldBe (40.25 plusOrMinus 1e-8)
        ground.longitudeDeg shouldBe (-3.5 plusOrMinus 1e-8)
    }

    @Test
    fun `S057 RF-12 un punto fuera del ambito del SC de origen se rechaza`() {
        val source = SourceGround(definition(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, -3.0)))

        source.toGround(5_000_000.0, 5_000_000.0)
            .shouldBeInstanceOf<GroundResult.Unavailable>().reason shouldBe PositionUnavailable.OUTSIDE_CRS_AREA
        source.toGround(Double.NaN, 1.0)
            .shouldBeInstanceOf<GroundResult.Unavailable>().reason shouldBe PositionUnavailable.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S057 RF-12 un punto fuera de la rejilla de la transformacion se rechaza`() {
        val definition = definition(23030, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))
        val projection = CrsDefinitions.resolve(definition, ClasspathGridSource("/shift/"))!!.projection!!
        val northOfGrid = projection.forward(46.0, -3.7)!!

        SourceGround(definition).toGround(northOfGrid.eastingM, northOfGrid.northingM)
            .shouldBeInstanceOf<GroundResult.Unavailable>().reason shouldBe PositionUnavailable.OUTSIDE_DATUM_GRID
    }

    @Test
    fun `S057 RF-10 una rejilla que no esta instalada da su nombre y no una coordenada`() {
        val definition = definition(23030, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))
        val v = vectors.first { it.origin == 23030 }
        val withoutGrids = object : LibraryResources {
            override val grids = GridSource { null }
            override fun geoid(gridName: String) = null
            override val generation = 0
        }

        val result = SourceGround(definition, withoutGrids).toGround(v.x, v.y).shouldBeInstanceOf<GroundResult.Unavailable>()

        result.reason shouldBe PositionUnavailable.MISSING_GRID
        result.missingGrid shouldBe "es_ign_SPED2ETV2.tif"
    }

    @Test
    fun `S057 RF-12 con el SC de origen igual al del proyecto no hay nada que reproyectar`() {
        SourceCrsResolver.resolve(catalog, SpainCrs.ETRS89_UTM30.epsg, SpainCrs.ETRS89_UTM30, null) shouldBe
            SourceCrsResolution.SameAsProject
    }

    @Test
    fun `S057 RF-2 otro SC se construye con el datum de las correcciones del proyecto`() {
        val resolution = SourceCrsResolver.resolve(catalog, 2100, SpainCrs.ETRS89_UTM30, GeoPoint(38.0, 23.5))

        val built = resolution.shouldBeInstanceOf<SourceCrsResolution.Other>().result.shouldBeInstanceOf<CrsBuildResult.Ready>()
        built.definition.frame shouldBe "ETRS89"
        built.definition.epsg shouldBe 2100
    }
}

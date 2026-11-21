package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * La cota de un ráster en un SC de la biblioteca en una posición del proyecto (S057/RF-15, RF-19, RF-20). Los ráster son sintéticos y planos
 * inclinados (la interpolación bilineal los reproduce exactamente) alrededor de un punto de `fixtures/geodesia/reproyeccion.csv`, así que la cota
 * en el punto del proyecto que da pyproj tiene que ser la del centro del ráster; la pendiente es de 0,1 y 0,2 m por metro y delata cualquier
 * desplazamiento de unos milímetros.
 */
class RasterElevationLibraryTest {
    private val catalog = CrsCatalog.bundled

    private data class Vector(
        val origin: Int,
        val project: Int,
        val lat: Double,
        val lon: Double,
        val x: Double,
        val y: Double,
        val e: Double,
        val n: Double,
    )

    private val vectors = SurveyFixtures.rows("reproyeccion.csv").map {
        val n = it.drop(4).map(String::toDouble)
        Vector(it[0].toInt(), it[1].toInt(), n[0], n[1], n[2], n[3], n[4], n[5])
    }

    private fun corrections(projectEpsg: Int) = if (projectEpsg == 32634) CorrectionsDatum.ITRF_WGS84 else CorrectionsDatum.ETRS89

    private fun definition(epsg: Int, frame: CorrectionsDatum, at: GeoPoint): CrsDefinition =
        when (val r = catalog.build(epsg, frame, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            else -> error("EPSG:$epsg: $r")
        }

    private class Plane(
        val columns: Int,
        val rows: Int,
        val originX: Double,
        val originY: Double,
        val step: Double,
        val centreX: Double,
        val centreY: Double,
    ) {
        fun value(col: Int, row: Int) = 100.0 + 0.1 * (originX + col * step - centreX) + 0.2 * (originY - row * step - centreY)
    }

    private fun planeAround(x: Double, y: Double, step: Double) = Plane(41, 41, x - 20 * step, y + 20 * step, step, x, y)

    private fun tiles(plane: Plane): RasterTileSource {
        val map = HashMap<Pair<Int, Int>, RasterTile>()
        var row = 0
        while (row < plane.rows) {
            val rows = minOf(RasterTile.EDGE, plane.rows - row)
            val band = DoubleArray(rows * plane.columns) { plane.value(it % plane.columns, row + it / plane.columns) }
            RasterTiler.cut(row, rows, plane.columns, band, RasterValueType.FLOAT64).forEach { map[it.tileColumn to it.tileRow] = it.tile }
            row += rows
        }
        return RasterTileSource { c, r -> map[c to r] }
    }

    private fun surface(
        plane: Plane,
        source: CadSourceCrs,
        heights: HeightSystem = HeightSystem.ORTHOMETRIC,
        step: Double = plane.step,
    ): Surface {
        val info = RasterInfo(plane.columns, plane.rows, plane.originX, plane.originY, step, step, RasterValueType.FLOAT64, heights, 1, 0)
        return Surface(
            "r", "p", "mdt", "mdt.tif", 0L, source, LinearUnit.METRE, SurfaceExtent(0.0, 1.0, 0.0, 1.0), 0.0, 0.0, 0, 0, 0,
            kind = SurfaceKind.RASTER, raster = info,
        )
    }

    private fun projectOf(v: Vector): ProjectCrs {
        val frame = corrections(v.project)
        return LibraryCrs(definition(v.project, frame, GeoPoint(v.lat, v.lon)), frame)
    }

    @Test
    fun `S057 RF-15 la cota de un raster en un SC de otro pais coincide con la de pyproj en cada punto de los cuatro casos`() {
        val failures = vectors.mapNotNull { v ->
            val source = CadSourceCrs.Library(definition(v.origin, corrections(v.project), GeoPoint(v.lat, v.lon)))
            val plane = planeAround(v.x, v.y, 100.0)
            val elevation = RasterElevation(projectOf(v), HeightSystem.ORTHOMETRIC, surface(plane, source), tiles(plane))

            val height = elevation.heightAt(v.e, v.n)

            val value = (height as? SurfaceHeight.Value)?.metres
            "EPSG:${v.origin} (${v.lat}, ${v.lon}): $height".takeIf { value == null || Math.abs(value - 100.0) > 0.001 }
        }
        failures shouldBe emptyList()
    }

    @Test
    fun `S057 RF-15 un raster en pies de un SC de la biblioteca lee en la malla la unidad del SC`() {
        val frame = CorrectionsDatum.ITRF_WGS84
        // El primer SC proyectado en pies, en orden de EPSG, que se puede calcular sin descargar rejillas, con el UTM de WGS 84 de su centro como proyecto.
        val (sourceDefinition, project, centre) = catalog.crs
            .filter {
                it.kind == es.topophone.core.geodesy.catalog.CrsKind.PROJECTED && it.unitMetres in 0.3048..0.30481 && it.area != null
            }
            .sortedBy { it.epsg }
            .firstNotNullOf { crs ->
                val area = crs.area!!
                val centre = GeoPoint((area.southDeg + area.northDeg) / 2, (area.westDeg + area.eastDeg) / 2)
                if (centre.latitudeDeg < 0.0) return@firstNotNullOf null
                val zone = ((centre.longitudeDeg + 180.0) / 6.0).toInt() + 1
                val source = catalog.build(crs.epsg, frame, centre) as? CrsBuildResult.Ready ?: return@firstNotNullOf null
                val utm = catalog.build(32600 + zone, frame, centre) as? CrsBuildResult.Ready ?: return@firstNotNullOf null
                Triple(source.definition, LibraryCrs(utm.definition, frame), centre)
            }
        val projection = es.topophone.core.geodesy.catalog.CrsDefinitions.resolve(
            sourceDefinition,
            BundledLibraryResources.grids,
        )!!.projection!!
        val inMetres = projection.forward(centre.latitudeDeg, centre.longitudeDeg)!!
        val x = inMetres.eastingM / sourceDefinition.unitMetres
        val y = inMetres.northingM / sourceDefinition.unitMetres
        val ground = SourceGround(sourceDefinition).toGround(x, y).shouldBeInstanceOf<GroundResult.Ok>()
        val position = LibraryEngine(project, BundledLibraryResources)
            .fromGround(GeodeticPosition(ground.latitudeDeg, ground.longitudeDeg, null), HeightSystem.ELLIPSOIDAL)
        val plane = planeAround(x, y, 100.0)

        val elevation = RasterElevation(
            project,
            HeightSystem.ORTHOMETRIC,
            surface(plane, CadSourceCrs.Library(sourceDefinition)),
            tiles(plane),
        )

        (elevation.heightAt(position.easting!!, position.northing!!) as SurfaceHeight.Value).metres shouldBe (100.0 plusOrMinus 0.001)
    }

    @Test
    fun `S057 RF-15 fuera de la extension del raster de la biblioteca la cota es fuera de la superficie`() {
        val v = vectors.first { it.origin == 2154 }
        val source = CadSourceCrs.Library(definition(v.origin, corrections(v.project), GeoPoint(v.lat, v.lon)))
        val plane = planeAround(v.x, v.y, 100.0)
        val elevation = RasterElevation(projectOf(v), HeightSystem.ORTHOMETRIC, surface(plane, source), tiles(plane))

        elevation.heightAt(v.e + 5_000.0, v.n) shouldBe SurfaceHeight.Outside
    }

    @Test
    fun `S057 RF-20 un raster con otro sistema de alturas sin geoide del proyecto es fuera de la superficie`() {
        val v = vectors.first { it.origin == 2154 }
        val source = CadSourceCrs.Library(definition(v.origin, corrections(v.project), GeoPoint(v.lat, v.lon)))
        val plane = planeAround(v.x, v.y, 100.0)
        val projectWithoutGeoid = projectOf(v) // un proyecto de la biblioteca sin geoide elegido (S034/RF-14)
        val elevation =
            RasterElevation(projectWithoutGeoid, HeightSystem.ORTHOMETRIC, surface(plane, source, HeightSystem.ELLIPSOIDAL), tiles(plane))

        elevation.heightAt(v.e, v.n) shouldBe SurfaceHeight.Outside
    }

    @Test
    fun `S057 RF-15 con el mismo sistema de alturas la cota no necesita geoide`() {
        val v = vectors.first { it.origin == 2154 }
        val source = CadSourceCrs.Library(definition(v.origin, corrections(v.project), GeoPoint(v.lat, v.lon)))
        val plane = planeAround(v.x, v.y, 100.0)
        val elevation = RasterElevation(projectOf(v), HeightSystem.ORTHOMETRIC, surface(plane, source), tiles(plane))

        (elevation.heightAt(v.e, v.n) as SurfaceHeight.Value).metres shouldBe (100.0 plusOrMinus 0.001)
    }

    @Test
    fun `S057 RF-19 con el geoide del proyecto la cota elipsoidal del raster pasa a ortometrica`() {
        val v = vectors.first { it.origin == 23030 }
        val frame = corrections(v.project)
        val source = CadSourceCrs.Library(definition(v.origin, frame, GeoPoint(v.lat, v.lon)))
        val project = LibraryCrs(definition(v.project, frame, GeoPoint(v.lat, v.lon)), frame, geoidGrid = "es_ign_egm08-rednap.tif")
        val plane = planeAround(v.x, v.y, 100.0)
        val ground = SourceGround(
            definition(v.origin, frame, GeoPoint(v.lat, v.lon)),
        ).toGround(v.x, v.y).shouldBeInstanceOf<GroundResult.Ok>()
        val undulation = es.topophone.core.geodesy.GeoidModels.egm08RednapPeninsula.undulationM(ground.latitudeDeg, ground.longitudeDeg)!!

        val elevation = RasterElevation(project, HeightSystem.ORTHOMETRIC, surface(plane, source, HeightSystem.ELLIPSOIDAL), tiles(plane))

        (elevation.heightAt(v.e, v.n) as SurfaceHeight.Value).metres shouldBe (100.0 - undulation plusOrMinus 0.001)
    }

    @Test
    fun `S057 RF-15 la extension de un raster en un SC de la biblioteca contiene el punto de pyproj y respeta la unidad`() {
        val v = vectors.first { it.origin == 3844 }
        val source = CadSourceCrs.Library(definition(v.origin, corrections(v.project), GeoPoint(v.lat, v.lon)))

        val footprint = RasterFootprint.of(projectOf(v), source, v.x - 2000.0, v.y + 2000.0, 100.0, 100.0, 41, 41)

        val extent = (footprint as RasterFootprint.Inside).extent
        (extent.minEasting < v.e && v.e < extent.maxEasting) shouldBe true
        (extent.minNorthing < v.n && v.n < extent.maxNorthing) shouldBe true
        (extent.maxEasting - extent.minEasting in 3990.0..4800.0) shouldBe true
    }
}

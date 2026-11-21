package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.cad.ImportArea
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.cos
import kotlin.math.sin
import org.junit.jupiter.api.Test

/** La zona de importación de un GeoPackage o un Shapefile con las capas en un SC de la biblioteca (S057/RF-16, S048/RF-13, RF-15). */
class ImportAreaFilterLibraryTest {
    private val catalog = CrsCatalog.bundled

    private fun definition(epsg: Int, frame: CorrectionsDatum, at: GeoPoint): CrsDefinition =
        when (val r = catalog.build(epsg, frame, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            else -> error("EPSG:$epsg: $r")
        }

    private val centre = GeoPoint(48.1, -3.0)
    private val central = CentralPoint(48.1, -3.0)
    private val project = LibraryCrs(definition(25830, CorrectionsDatum.ETRS89, centre))
    private val projectEngine = LibraryEngine(project, BundledLibraryResources)

    /** Puntos del círculo de la zona (en el plano del proyecto), de dentro hacia fuera hasta el radio, pasados al SC de la capa. */
    private fun layerPointsInside(source: CadSourceCrs.Library, radiusM: Double): List<Pair<Double, Double>> {
        val filter = ImportAreaFilter.of(project, ImportArea.Circle(central, radiusM)).shouldNotBeNull()
        val centreEngine = projectEngine.fromGround(
            es.topophone.core.model.point.GeodeticPosition(centre.latitudeDeg, centre.longitudeDeg, null),
            es.topophone.core.model.project.HeightSystem.ELLIPSOIDAL,
        )
        val sourceGround = SourceGround(source.definition)
        val points = ArrayList<Pair<Double, Double>>()
        for (ring in listOf(0.0, 0.5, 0.9, 0.999)) {
            for (i in 0 until 60) {
                val theta = 2 * Math.PI * i / 60
                val east = centreEngine.easting!! + ring * radiusM * sin(theta)
                val north = centreEngine.northing!! + ring * radiusM * cos(theta)
                filter.contains(east, north) shouldBe true
                val ground = projectEngine.groundFromGrid(east, north).shouldBeInstanceOf<GroundResult.Ok>()
                points.add(sourceGround.fromGround(ground.latitudeDeg, ground.longitudeDeg).shouldNotBeNull())
            }
        }
        return points
    }

    @Test
    fun `S057 RF-16 el recuadro de la zona en una capa en Lambert 93 contiene todo punto de la zona`() {
        val source = CadSourceCrs.Library(definition(2154, CorrectionsDatum.ETRS89, centre))
        val filter = ImportAreaFilter.of(project, ImportArea.Circle(central, 5_000.0)).shouldNotBeNull()

        val box = filter.candidateBox(source).shouldNotBeNull()

        layerPointsInside(source, 5_000.0).forEach { (x, y) ->
            (x in box.minX..box.maxX && y in box.minY..box.maxY) shouldBe true
        }
    }

    @Test
    fun `S057 RF-16 el recuadro en una capa geografica de la biblioteca viene en grados y contiene la zona`() {
        val source = CadSourceCrs.Library(definition(4326, CorrectionsDatum.ETRS89, centre))
        val filter = ImportAreaFilter.of(project, ImportArea.Circle(central, 2_000.0)).shouldNotBeNull()

        val box = filter.candidateBox(source).shouldNotBeNull()

        (box.minX < centre.longitudeDeg && centre.longitudeDeg < box.maxX) shouldBe true
        (box.minY < centre.latitudeDeg && centre.latitudeDeg < box.maxY) shouldBe true
        (box.maxX - box.minX < 0.1) shouldBe true
        layerPointsInside(source, 2_000.0).forEach { (x, y) ->
            (x in box.minX..box.maxX && y in box.minY..box.maxY) shouldBe true
        }
    }

    @Test
    fun `S057 RF-16 en una capa con otra escala que UTM el recuadro sigue conteniendo la zona`() {
        val source = CadSourceCrs.Library(definition(3857, CorrectionsDatum.ETRS89, centre))
        val filter = ImportAreaFilter.of(project, ImportArea.Circle(central, 3_000.0)).shouldNotBeNull()

        val box = filter.candidateBox(source).shouldNotBeNull()

        // En Mercator a 48° la escala es 1 / cos(48°) = 1,49: el recuadro mide más de lo que la zona en metros.
        ((box.maxX - box.minX) > 6_000.0 * 1.4) shouldBe true
        layerPointsInside(source, 3_000.0).forEach { (x, y) ->
            (x in box.minX..box.maxX && y in box.minY..box.maxY) shouldBe true
        }
    }

    @Test
    fun `S057 RF-16 si el centro no se puede pasar al SC de la capa no hay recuadro`() {
        val farCentral = CentralPoint(-30.0, 150.0)
        val source = CadSourceCrs.Library(definition(2154, CorrectionsDatum.ETRS89, centre))
        val filter = ImportAreaFilter.of(project, ImportArea.Circle(farCentral, 1_000.0))

        filter?.candidateBox(source).shouldBeNull()
    }

    @Test
    fun `S057 RF-16 en un proyecto de la biblioteca el filtro es proyectado y no geografico`() {
        val filter = ImportAreaFilter.of(project, ImportArea.Circle(central, 1_000.0)).shouldNotBeNull()
        val position = projectEngine.fromGround(
            es.topophone.core.model.point.GeodeticPosition(centre.latitudeDeg, centre.longitudeDeg, null),
            es.topophone.core.model.project.HeightSystem.ELLIPSOIDAL,
        )

        filter.contains(position.easting!! + 999.0, position.northing!!) shouldBe true
        filter.contains(position.easting!! + 1_001.0, position.northing!!) shouldBe false
    }

    @Test
    fun `S057 RF-27 el recuadro de un SC de Espana no cambia con la biblioteca`() {
        val spain = ImportAreaFilter.of(SpainCrs.ETRS89_UTM30, ImportArea.Circle(CentralPoint(40.4, -3.7), 1_000.0)).shouldNotBeNull()

        spain.candidateBox(CadSourceCrs.Utm(es.topophone.core.model.project.GeodeticDatum.ETRS89, 30)).shouldNotBeNull()
        spain.candidateBox(CadSourceCrs.Project).shouldBeNull()
    }
}

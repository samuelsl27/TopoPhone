package es.topophone.app.ui.map

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** «Ver coordenadas» del mapa con un SC de la biblioteca: lo mismo que con el de España y fuera de ámbito se avisa (S034/RF-28). */
class LibraryMapProjectionTest {
    private val calculator = ProjectPositionCalculator()
    private val area = VisibleArea(south = 40.0, west = -4.0, north = 41.0, east = -3.0, widthPx = 1080, heightPx = 1800)

    private val library: LibraryCrs = when (val result = CrsCatalog.bundled.build(25830, CorrectionsDatum.ETRS89, GeoPoint(40.5, -3.5))) {
        is CrsBuildResult.Ready -> LibraryCrs(result.definition)
        else -> error("$result")
    }

    private fun project(crs: ProjectCrs) = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = crs,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0L,
        modifiedAtMs = 0L,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun press(lat: Double, lon: Double) = MapPress.of(lat, lon, 540f, 900f, area)

    @Test
    fun `S034 RF-28 pulsar en el mapa da el Este y el Norte de la biblioteca igual que los de Espana`() {
        val spain = MapCoordinates.of(press(40.5, -3.5), project(ProjectCrs.ETRS89_UTM30), calculator).position
        val viaLibrary = MapCoordinates.of(press(40.5, -3.5), project(library), calculator).position

        viaLibrary.easting.shouldNotBeNull() shouldBe spain.easting.shouldNotBeNull().plusOrMinus(1e-6)
        viaLibrary.northing.shouldNotBeNull() shouldBe spain.northing.shouldNotBeNull().plusOrMinus(1e-6)
        MapCoordinates.of(press(40.5, -3.5), project(library), calculator).outOfScope.shouldBeNull()
    }

    @Test
    fun `S034 RF-28 fuera del ambito del SC de la biblioteca el mapa avisa`() {
        val far = VisibleArea(south = -34.0, west = 150.0, north = -33.0, east = 151.0, widthPx = 1080, heightPx = 1800)

        val result = MapCoordinates.of(MapPress.of(-33.5, 150.5, 540f, 900f, far), project(library), calculator)

        result.outOfScope shouldBe PositionUnavailable.OUTSIDE_CRS_AREA
    }
}

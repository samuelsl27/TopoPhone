package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.survey.geometry.GeometryVertex
import es.topophone.core.survey.geometry.GroundPosition
import es.topophone.core.survey.geometry.GroundScale
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Lo que usa un proyecto con un SC de la biblioteca: puntos manuales, posición del suelo y factores de escala (S034/RF-26, RF-27, RF-28). */
class LibraryProjectCoreTest {
    private val catalog = CrsCatalog.bundled
    private val manual = ManualCoordinates()

    private fun library(epsg: Int): LibraryCrs {
        val definition = when (val result = catalog.build(epsg, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))) {
            is CrsBuildResult.Ready -> result.definition
            is CrsBuildResult.NeedsGrids -> result.definition
            else -> error("$result")
        }
        return LibraryCrs(definition)
    }

    private fun typed(first: Double, second: Double, form: EntryForm = EntryForm.PROJECT_CRS, height: Double? = 650.0) =
        TypedCoordinates(form, first, second, height, HeightKind.ELLIPSOIDAL)

    @Test
    fun `S034 RF-26 un punto manual en ETRS89 UTM 30 de la biblioteca da el suelo y las coordenadas del de Espana`() {
        val spain = manual.compute(typed(440000.0, 4474000.0), SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
            .shouldBeInstanceOf<ManualResult.Computed>().coordinates
        val viaLibrary = manual.compute(typed(440000.0, 4474000.0), library(25830), HeightSystem.ELLIPSOIDAL)
            .shouldBeInstanceOf<ManualResult.Computed>().coordinates

        viaLibrary.ground.latitudeDeg shouldBe spain.ground.latitudeDeg.plusOrMinus(1e-12)
        viaLibrary.ground.longitudeDeg shouldBe spain.ground.longitudeDeg.plusOrMinus(1e-12)
        viaLibrary.easting shouldBe 440000.0 // lo escrito en el SC del proyecto se guarda tal cual
        viaLibrary.northing shouldBe 4474000.0
    }

    @Test
    fun `S034 RF-26 escribir latitud y longitud en un SC de la biblioteca da las coordenadas proyectadas`() {
        val result = manual.compute(typed(40.4, -3.7, EntryForm.GEOGRAPHIC), library(25830), HeightSystem.ELLIPSOIDAL)
            .shouldBeInstanceOf<ManualResult.Computed>().coordinates
        val spain = manual.compute(typed(40.4, -3.7, EntryForm.GEOGRAPHIC), SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)
            .shouldBeInstanceOf<ManualResult.Computed>().coordinates

        result.easting.shouldNotBeNull() shouldBe spain.easting.shouldNotBeNull().plusOrMinus(1e-6)
        result.northing.shouldNotBeNull() shouldBe spain.northing.shouldNotBeNull().plusOrMinus(1e-6)
    }

    @Test
    fun `S034 RF-26 un punto fuera del ambito del SC se rechaza`() {
        manual.compute(typed(-33.0, 151.0, EntryForm.GEOGRAPHIC), library(25830), HeightSystem.ELLIPSOIDAL) shouldBe
            ManualResult.Rejected(PositionUnavailable.OUTSIDE_CRS_AREA)
    }

    @Test
    fun `S034 RF-26 GroundPosition lleva un punto del SC de la biblioteca a latitud y longitud`() {
        val viaLibrary = GroundPosition.of(library(25830), 440000.0, 4474000.0).shouldNotBeNull()
        val viaSpain = GroundPosition.of(SpainCrs.ETRS89_UTM30, 440000.0, 4474000.0).shouldNotBeNull()

        viaLibrary.first shouldBe viaSpain.first.plusOrMinus(1e-12)
        viaLibrary.second shouldBe viaSpain.second.plusOrMinus(1e-12)
    }

    @Test
    fun `S034 RF-27 el factor de escala del terreno sale de la proyeccion de la biblioteca`() {
        val vertices = listOf(
            GeometryVertex(
                easting = 440000.0,
                northing = 4474000.0,
                height = 650.0,
                latitudeDeg = 40.4,
                longitudeDeg = -3.7,
                ellipsoidalHeightM = 650.0,
            ),
            GeometryVertex(
                easting = 440100.0,
                northing = 4474000.0,
                height = 650.0,
                latitudeDeg = 40.4,
                longitudeDeg = -3.699,
                ellipsoidalHeightM = 650.0,
            ),
        )

        val spain = GroundScale(SpainCrs.ETRS89_UTM30, null).segment(vertices[0], vertices[1])
        val viaLibrary = GroundScale(library(25830), null).segment(vertices[0], vertices[1])

        viaLibrary shouldBe spain.plusOrMinus(1e-12)
        (viaLibrary < 1.0) shouldBe true // la UTM reduce en el centro del huso y la altura la reduce más
    }

    @Test
    fun `S034 RF-27 el COGO toma el factor de la primera posicion tambien en un SC de la biblioteca`() {
        val scale = GroundScale(library(25830), null)

        scale.polygon(
            listOf(GeometryVertex(easting = 440000.0, northing = 4474000.0, height = 650.0, latitudeDeg = 40.4, longitudeDeg = -3.7)),
        ) shouldBe
            GroundScale(SpainCrs.ETRS89_UTM30, null).polygon(
                listOf(GeometryVertex(easting = 440000.0, northing = 4474000.0, height = 650.0, latitudeDeg = 40.4, longitudeDeg = -3.7)),
            ).plusOrMinus(1e-12)
    }
}

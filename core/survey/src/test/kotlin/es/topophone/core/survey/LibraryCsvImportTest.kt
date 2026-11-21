package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.ImportCoordinates
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Importar un CSV en un proyecto con un SC de la biblioteca (S034/RF-28; S028). */
class LibraryCsvImportTest {
    private val importer = PointImporter()
    private val options =
        ImportOptions(mapping = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.HEIGHT)))

    private fun library(epsg: Int): LibraryCrs =
        when (val result = CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7))) {
            is CrsBuildResult.Ready -> LibraryCrs(result.definition)
            else -> error("$result")
        }

    @Test
    fun `S034 RF-28 una fila con Este y Norte en un SC de la biblioteca da el mismo suelo que en el de Espana`() {
        val row = listOf("A1", "440000.5", "4474000.25", "650.0")

        val spain = importer.convert(
            row,
            options,
            SpainCrs.ETRS89_UTM30,
            HeightSystem.ELLIPSOIDAL,
        ).shouldBeInstanceOf<ImportRowResult.Valid>()
        val viaLibrary = importer.convert(
            row,
            options,
            library(25830),
            HeightSystem.ELLIPSOIDAL,
        ).shouldBeInstanceOf<ImportRowResult.Valid>()

        viaLibrary.coordinates.ground.latitudeDeg shouldBe spain.coordinates.ground.latitudeDeg.plusOrMinus(1e-12)
        viaLibrary.coordinates.ground.longitudeDeg shouldBe spain.coordinates.ground.longitudeDeg.plusOrMinus(1e-12)
        viaLibrary.coordinates.easting shouldBe 440000.5
        viaLibrary.coordinates.northing shouldBe 4474000.25
    }

    @Test
    fun `S034 RF-28 una fila que cae fuera del ambito del SC de la biblioteca se rechaza como fuera de ambito`() {
        val result = importer.convert(listOf("A1", "0.0", "0.0", "10.0"), options, library(25830), HeightSystem.ELLIPSOIDAL)

        // Este 0, Norte 0 en el huso 30N cae en el golfo de Guinea: fuera del ámbito de ETRS89 UTM 30.
        result shouldBe ImportRowResult.Invalid(ImportErrorReason.OUTSIDE_CRS_AREA)
        options.coordinates shouldBe ImportCoordinates()
    }

    @Test
    fun `S034 RF-28 una fila con latitud y longitud se importa en un SC geografico de la biblioteca`() {
        val geographicOptions = options.copy(coordinates = ImportCoordinates(form = es.topophone.core.model.point.EntryForm.GEOGRAPHIC))

        val result = importer.convert(listOf("A1", "40.4", "-3.7", "650.0"), geographicOptions, library(4258), HeightSystem.ELLIPSOIDAL)

        result.shouldBeInstanceOf<ImportRowResult.Valid>().coordinates.ground.latitudeDeg shouldBe 40.4.plusOrMinus(1e-9)
    }
}

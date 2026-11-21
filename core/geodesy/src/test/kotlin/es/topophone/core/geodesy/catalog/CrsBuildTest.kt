package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.TransformResult
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Elegir un SC de la biblioteca para un proyecto y guardar su definición (S034/RF-8, RF-10, RF-12, RF-13). */
class CrsBuildTest {
    private val catalog = CrsCatalog.bundled
    private val madrid = GeoPoint(40.4, -3.7)

    @Test
    fun `S034 RF-13 ETRS89 UTM 30 se elige sin descargar nada y su definicion es completa`() {
        val result = catalog.build(25830, CorrectionsDatum.ETRS89, madrid).shouldBeInstanceOf<CrsBuildResult.Ready>()

        result.definition.epsg shouldBe 25830
        result.definition.method shouldBe "tm"
        result.definition.transformationSteps shouldBe "null"
        result.definition.frame shouldBe "ETRS89"
        result.warnings.shouldBeEmpty()
    }

    @Test
    fun `S034 RF-12 un SC en ED50 con la rejilla del IGN no necesita descargar y uno que usa otra rejilla la pide`() {
        val ed50 = catalog.build(23030, CorrectionsDatum.ETRS89, madrid).shouldBeInstanceOf<CrsBuildResult.Ready>()
        ed50.definition.gridNames shouldBe listOf("es_ign_SPED2ETV2.tif")

        val needs = catalog.build(27700, CorrectionsDatum.ETRS89, GeoPoint(51.5, -0.12)).shouldBeInstanceOf<CrsBuildResult.NeedsGrids>()
        needs.grids.map { it.name } shouldBe listOf("uk_os_OSTN15_NTv2_OSGBtoETRS.tif")
        needs.grids.single().sizeBytes shouldBe catalog.grids.getValue("uk_os_OSTN15_NTv2_OSGBtoETRS.tif").sizeBytes

        catalog.build(27700, CorrectionsDatum.ETRS89, GeoPoint(51.5, -0.12), isInstalled = { true })
            .shouldBeInstanceOf<CrsBuildResult.Ready>()
    }

    @Test
    fun `S034 RF-10 se puede elegir otra transformacion de las ofrecidas`() {
        val picked = TransformationPicker.pick(catalog, 4230, CorrectionFrame.ETRS89, madrid)
        val other = picked.applicable.first { it.gridNames.isEmpty() }

        val result = catalog.build(23030, CorrectionsDatum.ETRS89, madrid, choice = other).shouldBeInstanceOf<CrsBuildResult.Ready>()

        result.definition.transformationName shouldBe other.name
    }

    @Test
    fun `S034 RF-10 con el centro fuera de todos los ambitos se propone la mas precisa y se avisa`() {
        val result = catalog.build(23030, CorrectionsDatum.ETRS89, GeoPoint(-30.0, 150.0))

        val warnings = when (result) {
            is CrsBuildResult.Ready -> result.warnings
            is CrsBuildResult.NeedsGrids -> result.warnings
            else -> error("Debía poderse crear: $result")
        }
        warnings shouldBe setOf(BuildWarning.TRANSFORMATION_OUTSIDE_AREA)
    }

    @Test
    fun `S034 RF-8 un SC sin transformacion hasta el datum de las correcciones no se puede elegir y dice por que`() {
        catalog.build(99999999, CorrectionsDatum.ETRS89, madrid) shouldBe CrsBuildResult.Unavailable(UnavailableReason.UNKNOWN_CRS)
        val withoutPath = catalog.crs.first { crs -> catalog.optionsFor(crs.datumCode, CorrectionFrame.ETRS89).isEmpty() }

        catalog.build(withoutPath.epsg, CorrectionsDatum.ETRS89, madrid) shouldBe
            CrsBuildResult.Unavailable(UnavailableReason.NO_TRANSFORMATION)
    }

    @Test
    fun `S034 RF-13 la definicion guardada calcula sin el catalogo lo mismo que con el`() {
        val ready = catalog.build(23030, CorrectionsDatum.ETRS89, madrid).shouldBeInstanceOf<CrsBuildResult.Ready>()
        val restored = CrsDefinition.parse(ready.definition.toText())

        val resolved = CrsDefinitions.resolve(restored, ClasspathGridSource("/shift/")).shouldNotBeNull()
        val projection = resolved.projection.shouldNotBeNull()
        val direct = catalog.crsByEpsg(23030).let { CrsBuilder.projection(it!!, catalog.datums.getValue(it.datumCode)) }.shouldNotBeNull()

        // Del suelo en ETRS89 al datum ED50 (inverso de la transformación) y de ahí a UTM 30.
        val ed50 = (resolved.transformation.inverse(GeodeticPoint(40.4, -3.7, 0.0)) as TransformResult.Ok).point
        val point = projection.forward(ed50.latitudeDeg, ed50.longitudeDeg).shouldNotBeNull()
        val expected = direct.forward(ed50.latitudeDeg, ed50.longitudeDeg).shouldNotBeNull()
        point.eastingM shouldBe expected.eastingM.plusOrMinus(1e-9)
        point.northingM shouldBe expected.northingM.plusOrMinus(1e-9)
        resolved.ellipsoid.semiMajorAxisM shouldBe 6378388.0
    }

    @Test
    fun `S034 RF-13 un SC geografico no tiene proyeccion y una definicion rota no se construye`() {
        val geographic = catalog.build(4326, CorrectionsDatum.ITRF_WGS84, madrid).shouldBeInstanceOf<CrsBuildResult.Ready>()
        CrsDefinitions.resolve(geographic.definition, ClasspathGridSource("/shift/")).shouldNotBeNull().projection shouldBe null

        val broken = geographic.definition.copy(projected = true, method = "tm", parameters = emptyMap())
        CrsDefinitions.resolve(broken, ClasspathGridSource("/shift/")) shouldBe null
    }
}

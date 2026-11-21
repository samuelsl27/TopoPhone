package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CorrectionFrame
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El cambio de datum de una importación: precisión, aviso, confirmación y rejillas que faltan (S057/RF-8, RF-9, RF-10). */
class ImportTransformationTest {
    private val catalog = CrsCatalog.bundled

    private fun transformation(
        epsg: Int,
        corrections: CorrectionsDatum,
        lat: Double,
        lon: Double,
        installed: (String) -> Boolean = {
            false
        },
    ) = ImportTransformation.of(catalog.build(epsg, corrections, GeoPoint(lat, lon), isInstalled = installed))

    @Test
    fun `S057 RF-9 con el mismo datum no hay transformacion ni aviso`() {
        transformation(2154, CorrectionsDatum.ETRS89, 48.0, -3.0).shouldBeNull()
    }

    @Test
    fun `S057 RF-8 la transformacion con su precision y su ambito se ensena en la vista previa`() {
        val t = transformation(2100, CorrectionsDatum.ITRF_WGS84, 38.0, 23.5).shouldNotBeNull()

        t.name shouldBe "GGRS87 to WGS 84 (1)"
        t.accuracyM shouldBe 1.0
        t.area.shouldNotBeNull()
        t.gridNames shouldBe emptyList()
        t.outsideArea shouldBe false
    }

    @Test
    fun `S057 RF-9 hasta 1 m no pide confirmacion y por encima si`() {
        transformation(2100, CorrectionsDatum.ITRF_WGS84, 38.0, 23.5).shouldNotBeNull().needsConfirmation shouldBe false

        val rough = transformation(3844, CorrectionsDatum.ETRS89, 44.43, 26.1).shouldNotBeNull()

        rough.accuracyM shouldBe 3.0
        rough.needsConfirmation shouldBe true
    }

    @Test
    fun `S057 RF-9 una precision no declarada pide confirmacion`() {
        val option = catalog.options.first { it.accuracyM == null && it.stepsText != "null" && it.area != null }
        val epsg = catalog.crs.first { it.datumCode == option.datumCode }.epsg
        val corrections = if (option.target == CorrectionFrame.ETRS89) CorrectionsDatum.ETRS89 else CorrectionsDatum.ITRF_WGS84
        val area = option.area!!
        val centre = GeoPoint((area.southDeg + area.northDeg) / 2, (area.westDeg + area.eastDeg) / 2)

        val result = catalog.build(epsg, corrections, centre)
        val t = ImportTransformation.of(result)

        // El catálogo propone la más precisa que cubre el centro, que puede ser otra de ese datum; solo se comprueba la regla.
        if (t != null && t.accuracyM == null) t.needsConfirmation shouldBe true
        ImportTransformation.of(definitionWith(result, accuracy = null)).shouldNotBeNull().needsConfirmation shouldBe true
    }

    private fun definitionWith(result: CrsBuildResult, accuracy: Double?): es.topophone.core.model.project.CrsDefinition {
        val definition = when (result) {
            is CrsBuildResult.Ready -> result.definition
            is CrsBuildResult.NeedsGrids -> result.definition
            is CrsBuildResult.Unavailable -> error("Sin definición: $result")
        }
        return definition.copy(transformationAccuracyM = accuracy, transformationSteps = "H;A=1,0;B=1,0;inv=0;conv=PV;p=1,0,0,0,0,0,0")
    }

    @Test
    fun `S057 RF-10 una rejilla que no esta instalada se pide y con ella instalada ya no`() {
        val needs = catalog.build(31467, CorrectionsDatum.ETRS89, GeoPoint(50.0, 8.5))
            .shouldBeInstanceOf<CrsBuildResult.NeedsGrids>()
        val t = ImportTransformation.of(needs).shouldNotBeNull()

        t.needsGrids shouldBe true
        t.missingGrids.shouldNotBeEmpty()
        t.gridNames.shouldNotBeEmpty()

        val installed = catalog.build(31467, CorrectionsDatum.ETRS89, GeoPoint(50.0, 8.5), isInstalled = { true })
        ImportTransformation.of(installed).shouldNotBeNull().needsGrids shouldBe false
    }

    @Test
    fun `S057 RF-8 si ninguna transformacion cubre la zona del fichero se avisa`() {
        val t = transformation(2100, CorrectionsDatum.ITRF_WGS84, 40.4, -3.7).shouldNotBeNull()

        t.outsideArea shouldBe true
    }

    @Test
    fun `S057 RF-8 un SC que no se puede usar no tiene transformacion`() {
        transformation(999999, CorrectionsDatum.ETRS89, 0.0, 0.0).shouldBeNull()
    }
}

package es.topophone.core.geodesy.catalog

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Lectura del catálogo de SC generado por `tools/geodesia/catalogo.py` (S034/RF-4). */
class CrsCatalogTest {
    private val catalog = CrsCatalog.bundled

    @Test
    fun `S034 RF-4 el catalogo incluido trae miles de SC, sus datums, transformaciones y rejillas`() {
        catalog.crs.size shouldBeGreaterThan 4500
        catalog.datums.size shouldBeGreaterThan 100
        catalog.options.size shouldBeGreaterThan 1000
        catalog.grids.size shouldBeGreaterThan 200
        catalog.countries.size shouldBeGreaterThan 100
    }

    @Test
    fun `S034 RF-4 ETRS89 UTM 30 trae sus parametros, su datum, su pais y su ambito`() {
        val utm30 = catalog.crsByEpsg(25830).shouldNotBeNull()

        utm30.kind shouldBe CrsKind.PROJECTED
        utm30.method shouldBe "tm"
        utm30.parameters["longitudeOfOriginDeg"] shouldBe (-3.0).plusOrMinus(1e-12)
        utm30.parameters["scaleFactor"] shouldBe 0.9996.plusOrMinus(1e-15)
        utm30.parameters["falseEasting"] shouldBe 500000.0
        utm30.unitMetres shouldBe 1.0
        utm30.countries shouldContain "ES"
        utm30.area.shouldNotBeNull().contains(40.0, -3.5) shouldBe true
        catalog.datums.getValue(utm30.datumCode).name shouldBe "ETRS89"
    }

    @Test
    fun `S034 RF-4 los SC geograficos y proyectados de otros paises estan en el catalogo`() {
        catalog.crsByEpsg(4326).shouldNotBeNull().kind shouldBe CrsKind.GEOGRAPHIC
        catalog.crsByEpsg(2154).shouldNotBeNull().method shouldBe "lcc2"
        catalog.crsByEpsg(3857).shouldNotBeNull().method shouldBe "webmerc"
        catalog.crsByEpsg(3035).shouldBeNull() // Lambert Azimuthal Equal Area: no se calcula
    }

    @Test
    fun `S034 RF-9 ED50 hasta ETRS89 ofrece la rejilla del IGN y los Helmert de EPSG, de mas a menos precisos`() {
        val options = catalog.optionsFor(4230, CorrectionFrame.ETRS89)

        options.any { it.gridNames == listOf("es_ign_SPED2ETV2.tif") } shouldBe true
        options.any { it.steps.first() is StepSpec.Helmert } shouldBe true
        val accuracies = options.mapNotNull { it.accuracyM }
        accuracies shouldBe accuracies.sorted()
    }

    @Test
    fun `S034 RF-4 un paso de Helmert lleva los elipsoides, la convencion y los parametros del catalogo`() {
        val helmert = catalog.optionsFor(4230, CorrectionFrame.ETRS89).first { it.name == "ED50 to ETRS89 (7)" }.steps.single()
            .shouldBeInstanceOf<StepSpec.Helmert>()

        helmert.before.semiMajorAxisM shouldBe 6378388.0
        helmert.before.inverseFlattening shouldBe 297.0
        helmert.after.semiMajorAxisM shouldBe 6378137.0
        helmert.inverse shouldBe false
        helmert.convention shouldBe "PV"
        helmert.translationM shouldBe Triple(-131.0, -100.3, -163.4)
        helmert.rotationArcSec shouldBe Triple(-1.244, -0.02, -1.144)
        helmert.scalePpm shouldBe 9.39
        helmert.pivotM.shouldBeNull()
    }

    @Test
    fun `S034 RF-9 la nula de ETRS89 y REGCAN95 no lleva pasos y la precision desconocida es null`() {
        catalog.optionsFor(4258, CorrectionFrame.ETRS89).single().steps shouldBe listOf(StepSpec.Null)
        val regcan = catalog.optionsFor(4081, CorrectionFrame.ETRS89).single()

        regcan.steps shouldBe listOf(StepSpec.Null)
        regcan.accuracyM.shouldBeNull()
    }

    @Test
    fun `S034 RF-12 RF-17 RF-19 RF-20 las rejillas traen tamano, SHA-256, licencia, fuente y si la app ya las incluye`() {
        val sped = catalog.grids.getValue("es_ign_SPED2ETV2.tif")
        val egm = catalog.grids.getValue("us_nga_egm08_25.tif")

        sped.kind shouldBe GridKind.HORIZONTAL
        sped.bundled shouldBe true
        sped.sha256 shouldBe "61896f5d74bdc7c1d5850839ae743b08e19f9a627e8febb4ac93353ded835961"
        sped.license.open shouldBe true
        egm.kind shouldBe GridKind.GEOID
        egm.bundled shouldBe false
        egm.sizeBytes shouldBeGreaterThan 1_000_000L
        egm.license.id shouldBe "PD"
        egm.sourceUrl.startsWith("http") shouldBe true
        egm.area.shouldNotBeNull().contains(0.0, 0.0) shouldBe true
    }

    @Test
    fun `S034 RF-4 los ambitos que cruzan el antimeridiano contienen los dos lados`() {
        val area = CatalogArea(-20.0, 170.0, -10.0, -170.0)

        area.contains(-15.0, 175.0) shouldBe true
        area.contains(-15.0, -175.0) shouldBe true
        area.contains(-15.0, 0.0) shouldBe false
        area.contains(0.0, 175.0) shouldBe false
    }

    @Test
    fun `S034 RF-4 una esfera del catalogo se lee con el aplanamiento inverso infinito`() {
        val text = "#ellipsoids\nEsfera\t6371000.0\t0.0\n#datums\n1\tD\tEsfera\t0.0\t\n#crs\n#options\n#grids\n#licenses\n#countries\n"

        CrsCatalog.parse(text).datums.getValue(1).ellipsoid.inverseFlattening shouldBe Double.POSITIVE_INFINITY
    }

    @Test
    fun `S034 RF-4 un catalogo sin una seccion o con un paso desconocido falla con un mensaje claro`() {
        assertThrows<IllegalStateException> { CrsCatalog.parse("#crs\n") }
        val withBadStep = "#ellipsoids\n#datums\n#crs\n#options\n1\tE\tn\t\t\tZ;x=1\n#grids\n#licenses\n#countries\n"
        assertThrows<IllegalStateException> { CrsCatalog.parse(withBadStep) }
    }
}

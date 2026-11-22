package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La validación del plano de terreno y el factor propuesto (S058/RF-2 a RF-5). */
class TerrainPlaneValidatorTest {
    private val utm30: ProjectCrs = SpainCrs.ETRS89_UTM30
    private val lambert93: LibraryCrs = (
        CrsCatalog.bundled.build(
            2154,
            CorrectionsDatum.ETRS89,
            GeoPoint(48.0, 2.0),
        ) as CrsBuildResult.Ready
        )
        .definition.let { LibraryCrs(it, CorrectionsDatum.ETRS89) }

    private fun errors(lat: Double?, lon: Double?, factor: Double?, crs: ProjectCrs = utm30) =
        TerrainPlaneValidator.validate(lat, lon, factor, crs)

    @Test
    fun `S058 RF-4 un plano valido no tiene errores y el factor 1 vale`() {
        errors(40.4, -3.7, 0.99954) shouldBe emptyMap()
        errors(40.4, -3.7, 1.0) shouldBe emptyMap()
    }

    @Test
    fun `S058 RF-4 un factor fuera de 0,9 a 1,1 o que no es un numero se marca`() {
        errors(40.4, -3.7, 0.89) shouldBe mapOf(TerrainField.FACTOR to TerrainError.OUT_OF_RANGE)
        errors(40.4, -3.7, 1.11) shouldBe mapOf(TerrainField.FACTOR to TerrainError.OUT_OF_RANGE)
        errors(40.4, -3.7, 0.9) shouldBe emptyMap()
        errors(40.4, -3.7, 1.1) shouldBe emptyMap()
        errors(40.4, -3.7, Double.NaN) shouldBe mapOf(TerrainField.FACTOR to TerrainError.NOT_FINITE)
        errors(40.4, -3.7, null) shouldBe mapOf(TerrainField.FACTOR to TerrainError.REQUIRED)
        errors(40.4, -3.7, 0.0) shouldBe mapOf(TerrainField.FACTOR to TerrainError.OUT_OF_RANGE)
    }

    @Test
    fun `S058 RF-5 un origen fuera del ambito del SC se marca`() {
        errors(40.4, -3.7, 1.0, lambert93) shouldBe mapOf(TerrainField.ORIGIN to TerrainError.OUT_OF_AREA)
        errors(48.85, 2.35, 1.0, lambert93) shouldBe emptyMap()
    }

    @Test
    fun `S058 RF-5 un origen que no es una posicion o que falta se marca`() {
        errors(null, -3.7, 1.0) shouldBe mapOf(TerrainField.ORIGIN to TerrainError.REQUIRED)
        errors(40.4, null, 1.0) shouldBe mapOf(TerrainField.ORIGIN to TerrainError.REQUIRED)
        errors(95.0, -3.7, 1.0) shouldBe mapOf(TerrainField.ORIGIN to TerrainError.OUT_OF_RANGE)
        errors(40.4, 200.0, 1.0) shouldBe mapOf(TerrainField.ORIGIN to TerrainError.OUT_OF_RANGE)
        errors(Double.NaN, -3.7, 1.0) shouldBe mapOf(TerrainField.ORIGIN to TerrainError.NOT_FINITE)
    }

    @Test
    fun `S058 RF-2 con un SC geografico no se ofrece terreno`() {
        errors(40.4, -3.7, 1.0, SpainCrs.ETRS89_GEOGRAPHIC) shouldBe mapOf(TerrainField.CRS to TerrainError.NOT_PROJECTED)
    }

    @Test
    fun `S058 RF-3 sin altura el factor propuesto es el de escala de la proyeccion y con altura es menor`() {
        val flat = TerrainProposal.factorAt(40.4, -3.7, null, utm30).shouldNotBeNull()
        val high = TerrainProposal.factorAt(40.4, -3.7, 650.0, utm30).shouldNotBeNull()

        // En UTM 30N a -3,7 de longitud la escala de la proyeccion es 0,99960 y una altura de 650 m la baja unas 1e-4.
        flat shouldBe (0.99960 plusOrMinus 1e-4)
        (high < flat) shouldBe true
        high shouldBe (flat * 6_371_000.0 / (6_371_000.0 + 650.0) plusOrMinus 2e-6)
    }

    @Test
    fun `S058 RF-3 con un SC geografico o un origen fuera del ambito no hay propuesta`() {
        TerrainProposal.factorAt(40.4, -3.7, 0.0, SpainCrs.ETRS89_GEOGRAPHIC).shouldBeNull()
        TerrainProposal.factorAt(40.4, -3.7, 0.0, lambert93).shouldBeNull()
    }
}

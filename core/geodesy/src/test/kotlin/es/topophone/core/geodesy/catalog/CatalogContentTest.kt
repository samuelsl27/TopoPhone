package es.topophone.core.geodesy.catalog

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Qué métodos de proyección hay en el catálogo y que todos se construyen (S034/RF-4). */
class CatalogContentTest {
    private val catalog = CrsCatalog.bundled

    @Test
    fun `S034 RF-4 el catalogo solo tiene metodos que se calculan`() {
        val methods = catalog.crs.map { it.method }.toSet()

        methods shouldContainAll listOf("tm", "lcc1", "lcc2", "merca", "mercb", "omerca", "omercb", "stereo", "webmerc", "geo")
        (
            methods -
                setOf("tm", "lcc1", "lcc2", "merca", "mercb", "omerca", "omercb", "stereo", "polara", "polarb", "polarc", "webmerc", "geo")
            )
            .shouldBeEmpty()
    }

    @Test
    fun `S034 RF-4 todo SC proyectado del catalogo se construye y todo SC geografico no proyecta`() {
        val broken = catalog.crs.filter { crs ->
            val projection = CrsBuilder.projection(crs, catalog.datums.getValue(crs.datumCode))
            (crs.kind == CrsKind.PROJECTED) == (projection == null)
        }.map { it.epsg }

        broken.shouldBeEmpty()
    }

    @Test
    fun `S034 RF-4 una Transversa de Mercator con latitud de origen distinta de cero resta el Norte del origen`() {
        val os = catalog.crsByEpsg(27700).shouldNotBeNull() // OSGB36 / British National Grid, origen en 49°N
        val projection = CrsBuilder.projection(os, catalog.datums.getValue(os.datumCode)).shouldNotBeNull()

        val origin = projection.forward(49.0, -2.0).shouldNotBeNull()

        origin.eastingM shouldBe 400000.0.plusOrMinus(1e-6)
        origin.northingM shouldBe (-100000.0).plusOrMinus(1e-6)
    }

    @Test
    fun `S034 RF-4 los SC geograficos no tienen proyeccion y el pie topografico de EE UU se aplica a los falsos`() {
        val geographic = catalog.crsByEpsg(4326).shouldNotBeNull()
        CrsBuilder.projection(geographic, catalog.datums.getValue(geographic.datumCode)).shouldBeNull()

        val texas = catalog.crsByEpsg(32040).shouldNotBeNull() // NAD27 / Texas South Central, en pies de EE. UU.
        texas.unitMetres shouldBe (1200.0 / 3937.0).plusOrMinus(1e-15)
    }
}

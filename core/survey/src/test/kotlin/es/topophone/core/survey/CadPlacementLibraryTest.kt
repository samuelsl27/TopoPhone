package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Dónde cae un punto del proyecto en geográficas, con un proyecto de España y con uno de la biblioteca (S057/RF-13; S040/RF-9). */
class CadPlacementLibraryTest {
    private val library = LibraryCrs(
        (CrsCatalog.bundled.build(25830, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)) as CrsBuildResult.Ready).definition,
    )

    @Test
    fun `S057 RF-13 un proyecto de la biblioteca da las mismas geograficas que el de Espana`() {
        val spain = CadPlacement.geographic(SpainCrs.ETRS89_UTM30, 440_000.0, 4_474_000.0).shouldNotBeNull()
        val lib = CadPlacement.geographic(library, 440_000.0, 4_474_000.0).shouldNotBeNull()

        lib.first shouldBe (spain.first plusOrMinus 1e-9)
        lib.second shouldBe (spain.second plusOrMinus 1e-9)
    }

    @Test
    fun `S057 RF-27 en un proyecto geografico de Espana (Norte, Este) siguen siendo latitud y longitud`() {
        CadPlacement.geographic(SpainCrs.ETRS89_GEOGRAPHIC, -3.7, 40.4) shouldBe (40.4 to -3.7)
    }

    @Test
    fun `S057 RF-27 en ED50 fuera de la rejilla no hay posicion`() {
        CadPlacement.geographic(SpainCrs.ED50_UTM30, 440_000.0, 6_000_000.0) shouldBe null
    }
}

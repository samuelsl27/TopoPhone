package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadImportOptions
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * Los formatos con un SC de España no cambian con S057: un dibujo en un UTM de España da lo mismo con la ruta de España que con el mismo SC elegido
 * en la biblioteca, y lo guardado antes de S057 se lee igual (S057/RF-27; S040, S042, S045, S048, S049).
 */
class SpainFormatsUnchangedTest {
    private val catalog = CrsCatalog.bundled
    private val project = SpainCrs.ETRS89_UTM30

    private fun library(epsg: Int, at: GeoPoint): CadSourceCrs.Library {
        val definition = when (val r = catalog.build(epsg, CorrectionsDatum.ETRS89, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg: $r")
        }
        return CadSourceCrs.Library(definition)
    }

    private fun point(x: Double, y: Double) = CadEntity(CadEntityKind.POINT, doubleArrayOf(x), doubleArrayOf(y), null, null)

    private fun convert(source: CadSourceCrs, x: Double, y: Double): Pair<Double, Double> {
        val entity = CadProjector(project, CadImportOptions(source = source)).convert(point(x, y))
        val converted = entity.shouldBeInstanceOf<CadProjection.Converted>().entity
        return converted.easting[0] to converted.northing[0]
    }

    @Test
    fun `S057 RF-27 un UTM de Espana da lo mismo con la ruta de Espana que elegido en la biblioteca`() {
        // (huso, EPSG de ETRS89 en ese huso, un punto de la Peninsula dentro del huso)
        val cases = listOf(
            Triple(29, 25829, 650_000.0 to 4_480_000.0),
            Triple(30, 25830, 440_000.0 to 4_474_000.0),
            Triple(
                31,
                25831,
                420_000.0 to 4_600_000.0,
            ),
        )
        val failures = cases.mapNotNull { (zone, epsg, xy) ->
            val spain = convert(CadSourceCrs.Utm(GeodeticDatum.ETRS89, zone), xy.first, xy.second)
            val at = GeoPoint(40.4, -3.7)
            val lib = convert(library(epsg, at), xy.first, xy.second)
            val error = hypot(spain.first - lib.first, spain.second - lib.second)
            "UTM $zone: $error m".takeIf { error > 0.001 }
        }
        failures.shouldBeEmpty()
    }

    @Test
    fun `S057 RF-27 en ED50 la ruta de Espana y la de la biblioteca coinciden con la rejilla SPED2ETV2 en el centro de la Peninsula`() {
        val spain = convert(CadSourceCrs.Utm(GeodeticDatum.ED50, 30), 440_595.387, 4_472_467.210)
        val lib = convert(library(23030, GeoPoint(40.4, -3.7)), 440_595.387, 4_472_467.210)

        hypot(spain.first - lib.first, spain.second - lib.second) shouldBe (0.0 plusOrMinus 0.001)
    }

    @Test
    fun `S057 RF-27 lo guardado antes de S057 se lee igual`() {
        listOf("PROJECT", "UTM:ETRS89:30", "UTM:ED50:29", "GEO:REGCAN95").forEach { text ->
            CadSourceCrs.decode(text).encode() shouldBe text
        }
        CadSourceCrs.decode("UTM:ED50:30") shouldBe CadSourceCrs.Utm(GeodeticDatum.ED50, 30)
        CadSourceCrs.decode("GEO:ETRS89") shouldBe CadSourceCrs.Geographic(GeodeticDatum.ETRS89)
    }
}

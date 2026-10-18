package es.topophone.core.geodesy

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Geoide EGM08-REDNAP de Canarias (S008/RF-4; ≤ 1 mm frente a PROJ) y tamaño de las rejillas nuevas (RNF de S008). */
class GeoidCanariasTest {

    private val rows = GeodesyFixtures.rows("geoide_egm08_rednap_canarias.csv")

    @Test
    fun `S008 RF-4 la ondulacion del geoide de Canarias coincide con PROJ a menos de 1 mm`() {
        val inside = rows.filter { it[2].isNotEmpty() }
        inside.size shouldBeGreaterThan 50

        val failures = inside.mapNotNull { (lat, lon, expected) ->
            val n = GeoidModels.egm08RednapCanarias.undulationM(lat.toDouble(), lon.toDouble())
            when {
                n == null -> "sin valor en ($lat, $lon)"
                abs(n - expected.toDouble()) > 0.001 -> "($lat, $lon): ${n - expected.toDouble()} m"
                else -> null
            }
        }

        failures.shouldBeEmpty()
    }

    @Test
    fun `S008 RF-4 fuera de su cobertura no hay ondulacion y no se extrapola`() {
        val outside = rows.filter { it[2].isEmpty() }
        outside.size shouldBeGreaterThan 4

        outside.forEach { (lat, lon) -> GeoidModels.egm08RednapCanarias.undulationM(lat.toDouble(), lon.toDouble()).shouldBeNull() }
    }

    @Test
    fun `S008 RF-4 en Santa Cruz de Tenerife hay ondulacion del geoide de Canarias y no de la Peninsula`() {
        GeoidModels.egm08RednapCanarias.undulationM(28.4636, -16.2518).shouldNotBeNull()
        GeoidModels.egm08RednapPeninsula.undulationM(28.4636, -16.2518).shouldBeNull()
    }

    @Test
    fun `S008 RNF las rejillas de ED50 y de Canarias ocupan menos de 300 KB`() {
        fun size(path: String) = GeoidCanariasTest::class.java.getResource(path).shouldNotBeNull().openConnection().contentLengthLong

        (size("/geoid/es_ign_egm08-rednap-canarias.tif") + size("/shift/es_ign_SPED2ETV2.tif")) shouldBeLessThan 300L * 1024
    }
}

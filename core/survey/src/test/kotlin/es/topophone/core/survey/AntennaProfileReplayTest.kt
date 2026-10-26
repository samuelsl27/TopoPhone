package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaParser
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.model.point.PoleKind
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * S046/RF-20, RF-21: las sesiones grabadas de `fixtures/nmea/` con el RS3 y un jalón de 1,800 m dan lo mismo que antes
 * (RecordedSessionsTest, pyproj a 1 mm); con otro perfil o con un jalón inclinado solo cambia la cota del suelo, en lo que
 * cambia la altura de antena, y la planta y las alturas del geoide no se tocan. Lo esperado se calcula aparte: la cota con el
 * RS3 menos la diferencia de alturas de antena.
 */
class AntennaProfileReplayTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        ?: error("No se encuentra la raíz del repositorio")

    private val sessions: List<File> = File(root, "fixtures/nmea").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".nmea") }
        .sortedBy { it.path }
        .toList()

    private val rs4 = AntennaCatalog.ALL.first { it.ref == "catalog:RS4" }
    private val own = AntennaProfile("own:test", "Propio", 0.12, 0.06, builtIn = false)

    @TestFactory
    fun `S046 RF-20 y RF-21 el RS3 da lo de siempre y otro perfil solo cambia la cota del suelo`(): List<DynamicTest> {
        sessions.shouldNotBeEmpty()
        return sessions.map { session -> DynamicTest.dynamicTest(session.relativeTo(root).path) { check(session) } }
    }

    private fun check(session: File) {
        val fixes = NmeaFramer().feed(session.readBytes()).mapNotNull(GgaParser::parse)
        fixes.shouldNotBeEmpty()
        val rs3 = GroundPointCalculator()
        val withRs4 = GroundPointCalculator(profile = rs4)
        val withOwn = GroundPointCalculator(profile = own)
        // Un jalón inclinado de 1,95 m con el radio de 0,06 m: su altura vertical sale de la raíz.
        val slant = GearResolver.resolve("own:test", listOf(own), "s", listOf(SavedPole("s", "Inclinado", PoleKind.SLANT, 1.95)))
        val slantPole = slant.verticalPoleM.shouldNotBeNull()
        fixes.forEach { fix ->
            withClue("${session.name} ${fix.utcTime}") {
                val base = rs3.compute(fix, 1.8)
                val other = withRs4.compute(fix, 1.8)
                val third = withOwn.compute(fix, slantPole)
                if (base == null) {
                    other.shouldBeNull()
                    third.shouldBeNull()
                    return@forEach
                }
                other.shouldNotBeNull()
                third.shouldNotBeNull()
                other.latitudeDeg shouldBe base.latitudeDeg
                other.longitudeDeg shouldBe base.longitudeDeg
                other.utm shouldBe base.utm
                base.ellipsoidalHeightM?.let { h ->
                    other.ellipsoidalHeightM.shouldNotBeNull() shouldBe (h + (0.134 - 0.095) plusOrMinus MM)
                    third.ellipsoidalHeightM.shouldNotBeNull() shouldBe (h + (1.8 + 0.134) - (slantPole + 0.12) plusOrMinus MM)
                }
                base.orthometricHeightM?.let { orthometric ->
                    other.orthometricHeightM.shouldNotBeNull() shouldBe (orthometric + (0.134 - 0.095) plusOrMinus MM)
                }
            }
        }
    }

    private companion object {
        const val MM = 1e-9
    }
}

package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaParser
import es.topophone.core.gnss.nmea.NmeaFramer
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
 * Puerta G0, ensayo (a): cada sesión de `fixtures/nmea/` con su `.esperado.csv` (pyproj aplicado a
 * sus GGA; `tools/validacion/g0_sesion.py --esperado` para las grabadas en campo) se calcula con la
 * cadena de TopoPhone y se compara a 1 mm (S002/RF-11, S002/RF-32, S002/RF-33, S002/RF-36 a S002/RF-38).
 */
class RecordedSessionsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        ?: error("No se encuentra la raíz del repositorio")

    private val sessions: List<File> = File(root, "fixtures/nmea").walkTopDown()
        .filter { it.isFile && it.name.endsWith(".nmea") && expectedFor(it).isFile }
        .sortedBy { it.path }
        .toList()

    private fun expectedFor(session: File) = File(session.parentFile, session.name.removeSuffix(".nmea") + ".esperado.csv")

    @TestFactory
    fun `S002 RF-32 a RF-38 las sesiones grabadas dan lo mismo que pyproj`(): List<DynamicTest> {
        sessions.shouldNotBeEmpty()
        return sessions.map { session -> DynamicTest.dynamicTest(session.relativeTo(root).path) { check(session) } }
    }

    private fun check(session: File) {
        val lines = expectedFor(session).readLines(Charsets.UTF_8)
        val pole = lines.first { it.startsWith("# jalon_m=") }.substringAfter("jalon_m=").substringBefore(';').toDouble()
        val data = lines.filter { it.isNotBlank() && !it.startsWith("#") }
        val header = data.first().split(',')
        val expected = data.drop(1).map { header.zip(it.split(',')).toMap() }
        val fixes = NmeaFramer().feed(session.readBytes()).mapNotNull(GgaParser::parse)

        fixes.map { it.utcTime } shouldBe expected.map { it.getValue("hora") } // S002/RF-11: las mismas GGA válidas
        val calculator = GroundPointCalculator()
        fixes.zip(expected).forEach { (fix, row) ->
            withClue("${session.name} ${fix.utcTime}") { compare(calculator.compute(fix, pole), row) }
        }
    }

    private fun compare(point: GroundPoint?, row: Map<String, String>) {
        fun value(name: String) = row.getValue(name).takeIf { it.isNotEmpty() }?.toDouble()
        val latitude = value("latitud")
        if (latitude == null) {
            point.shouldBeNull()
            return
        }
        point.shouldNotBeNull()
        point.latitudeDeg shouldBe (latitude plusOrMinus 1e-12)
        point.longitudeDeg shouldBe (value("longitud")!! plusOrMinus 1e-12)
        point.ellipsoidalHeightM.near(value("h"))
        val east = value("este")
        if (east == null) {
            point.utm.shouldBeNull()
        } else {
            val utm = point.utm.shouldNotBeNull()
            utm.zone shouldBe row.getValue("huso").toInt()
            utm.easting shouldBe (east plusOrMinus MM)
            utm.northing shouldBe (value("norte")!! plusOrMinus MM)
        }
        point.orthometricHeightM.near(value("H"))
    }

    private fun Double?.near(expected: Double?) {
        if (expected == null) shouldBeNull() else shouldNotBeNull() shouldBe (expected plusOrMinus MM)
    }

    private companion object {
        const val MM = 0.001
    }
}

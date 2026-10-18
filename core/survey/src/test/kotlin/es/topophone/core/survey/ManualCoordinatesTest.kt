package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import org.junit.jupiter.api.Test

/** Punto manual: de lo escrito al punto del suelo y al SC del proyecto, frente a pyproj (S011/RF-17 a RF-20; constitución nº 2). */
class ManualCoordinatesTest {

    private val manual = ManualCoordinates()

    /** Una fila de `puntos_manuales.csv`. */
    private data class Case(
        val crs: ProjectCrs,
        val heights: HeightSystem,
        val typed: TypedCoordinates,
        val latitude: Double?,
        val longitude: Double?,
        val ellipsoidal: Double?,
        val easting: Double?,
        val northing: Double?,
        val projectHeight: Double?,
        val reason: String,
    )

    private val cases: List<Case> = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/geodesia/puntos_manuales.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { line ->
                val c = line.split(',')
                fun d(i: Int) = c[i].takeIf { it.isNotEmpty() }?.toDouble()
                Case(
                    crs = ProjectCrs.of(GeodeticDatum.valueOf(c[0]), c[1].toIntOrNull()).shouldNotBeNull(),
                    heights = HeightSystem.valueOf(c[2]),
                    typed = TypedCoordinates(EntryForm.valueOf(c[3]), c[4].toDouble(), c[5].toDouble(), d(6), HeightKind.valueOf(c[7])),
                    latitude = d(8),
                    longitude = d(9),
                    ellipsoidal = d(10),
                    easting = d(11),
                    northing = d(12),
                    projectHeight = d(13),
                    reason = c[14],
                )
            }
    }

    /** Diferencias de más de 1 mm con pyproj, en metros sobre el terreno. */
    private fun differences(case: Case): String? {
        val result = manual.compute(case.typed, case.crs, case.heights)
        if (case.reason.isNotEmpty()) {
            return if (result == ManualResult.Rejected(PositionUnavailable.valueOf(case.reason))) null else "$case → $result"
        }
        val coordinates = (result as? ManualResult.Computed)?.coordinates ?: return "$case → $result"
        val ground = coordinates.ground
        val problems = buildList {
            val dNorth = (ground.latitudeDeg - case.latitude!!) * 111_120.0
            val dEast = (ground.longitudeDeg - case.longitude!!) * 111_120.0 * cos(Math.toRadians(case.latitude))
            if (abs(dNorth) > 0.001 || abs(dEast) > 0.001) add("Δφ=${dNorth * 1000} mm Δλ=${dEast * 1000} mm")
            fun compare(label: String, actual: Double?, expected: Double?) {
                if ((actual == null) != (expected == null) ||
                    (actual != null && abs(actual - expected!!) > 0.001)
                ) {
                    add("$label $actual ≠ $expected")
                }
            }
            compare("h", ground.ellipsoidalHeightM, case.ellipsoidal)
            compare("E", coordinates.easting, case.easting)
            compare("N", coordinates.northing, case.northing)
            compare("H", coordinates.height, case.projectHeight)
        }
        return if (problems.isEmpty()) null else "$case: $problems"
    }

    @Test
    fun `S011 RF-17 lo escrito en el SC del proyecto da el punto del suelo y las coordenadas de pyproj`() {
        val projected = cases.filter { it.typed.form == EntryForm.PROJECT_CRS && it.reason.isEmpty() }
        projected.map { it.crs.datum }.toSet() shouldBe GeodeticDatum.entries.toSet()
        projected.size shouldBeGreaterThan 100

        projected.mapNotNull(::differences).shouldBeEmpty()
    }

    @Test
    fun `S011 RF-17 lo escrito en geograficas del datum del proyecto, con altitud o elipsoidal, da lo mismo que pyproj`() {
        val geographic = cases.filter { it.typed.form == EntryForm.GEOGRAPHIC && it.reason.isEmpty() }
        geographic.map { it.typed.heightKind }.toSet() shouldBe HeightKind.entries.toSet()
        geographic.size shouldBeGreaterThan 100

        geographic.mapNotNull(::differences).shouldBeEmpty()
    }

    @Test
    fun `S011 RF-18 con la altura vacia el punto se guarda sin altura`() {
        val flat = cases.filter { it.typed.height == null && it.reason.isEmpty() }
        flat.size shouldBeGreaterThan 50

        flat.mapNotNull(::differences).shouldBeEmpty()
        flat.forEach { case ->
            val coordinates = (manual.compute(case.typed, case.crs, case.heights) as ManualResult.Computed).coordinates
            coordinates.ground.ellipsoidalHeightM shouldBe null
            coordinates.height shouldBe null
        }
    }

    @Test
    fun `S011 RF-19 fuera del ambito, de la rejilla ED50 o del geoide con altitud no se guarda y se dice por que`() {
        val rejected = cases.filter { it.reason.isNotEmpty() }
        rejected.map { it.reason }.toSet() shouldBe setOf("OUTSIDE_CRS_AREA", "OUTSIDE_ED50_GRID", "OUTSIDE_GEOID")

        rejected.mapNotNull(::differences).shouldBeEmpty()
    }

    @Test
    fun `S011 RF-20 en el SC del proyecto se guardan Este, Norte y altura tal como se escribieron`() {
        cases.filter { it.typed.form == EntryForm.PROJECT_CRS && it.reason.isEmpty() && it.typed.height != null }.forEach { case ->
            val coordinates = (manual.compute(case.typed, case.crs, case.heights) as ManualResult.Computed).coordinates
            coordinates.easting shouldBe case.typed.first
            coordinates.northing shouldBe case.typed.second
            coordinates.height shouldBe case.typed.height
        }
    }

    @Test
    fun `S011 RF-14 fromGround da lo mismo que compute desde la GGA con el jalon restado`() {
        val calculator = ProjectPositionCalculator()
        val fix = GgaFix("101530.20", 40.41668, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")
        val ground = GeodeticPosition(40.41668, -3.70371, 650.123 + 51.234 - 1.8 - ReceiverModel.RS3.baseToReferenceM)

        for (crs in ProjectCrs.entries) {
            for (heights in crs.heightSystems) {
                calculator.fromGround(ground, crs, heights) shouldBe calculator.compute(fix, 1.8, crs, heights)
            }
        }
    }
}

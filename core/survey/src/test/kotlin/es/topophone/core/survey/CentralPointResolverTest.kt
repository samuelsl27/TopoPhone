package es.topophone.core.survey

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.project.CentralPoint
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import org.junit.jupiter.api.Test

/** Fijar el punto central: coordenadas escritas, toque del mapa y posición del receptor (S006/RF-33, RF-34). */
class CentralPointResolverTest {
    private val resolver = CentralPointResolver()

    private class Row(val crs: ProjectCrs, val form: EntryForm, val first: Double, val second: Double, val lat: Double, val lon: Double)

    /** Filas de `puntos_manuales.csv` (pyproj) que se aceptan, sin repetir las que solo cambian en la altura. */
    private val rows: List<Row> = run {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/geodesia/puntos_manuales.csv").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split(',') }
            .filter { it[14].isEmpty() && it[8].isNotEmpty() }
            .distinctBy { listOf(it[0], it[1], it[3], it[4], it[5]) }
            .map { c ->
                Row(
                    ProjectCrs.of(GeodeticDatum.valueOf(c[0]), c[1].toIntOrNull()).shouldNotBeNull(),
                    EntryForm.valueOf(c[3]),
                    c[4].toDouble(),
                    c[5].toDouble(),
                    c[8].toDouble(),
                    c[9].toDouble(),
                )
            }
    }

    private fun fix(lat: Double, lon: Double, status: SolutionStatus = SolutionStatus.FIX) =
        GgaFix("120000.00", lat, lon, status, 12, 0.8, 100.0, 50.0, 1.0, "")

    @Test
    fun `S006 RF-33 lo escrito en el SC del proyecto o en geograficas da lo mismo que pyproj`() {
        rows.map { it.crs.datum }.toSet() shouldBe GeodeticDatum.entries.toSet()
        rows.size shouldBeGreaterThan 50

        val differences = rows.mapNotNull { row ->
            val result = resolver.fromTyped(row.form, row.first, row.second, row.crs)
            val point = (result as? CentralPointResult.Fixed)?.point ?: return@mapNotNull "$row → $result"
            val dNorth = (point.latitudeDeg - row.lat) * 111_120.0
            val dEast = (point.longitudeDeg - row.lon) * 111_120.0 * cos(Math.toRadians(row.lat))
            if (abs(dNorth) > 0.001 || abs(dEast) > 0.001) "Δ=($dNorth, $dEast) m" else null
        }
        differences.shouldBeEmpty()
    }

    @Test
    fun `S006 RF-33 un toque del mapa dentro del ambito se guarda sin redondear`() {
        val result = resolver.fromTap(39.4699123456789, -0.3763123456789, ProjectCrs.ETRS89_UTM30)

        result shouldBe CentralPointResult.Fixed(CentralPoint(39.4699123456789, -0.3763123456789))
    }

    @Test
    fun `S006 RF-33 la posicion del receptor es el punto central, sea cual sea su solucion`() {
        val result = resolver.fromFix(fix(39.4699123456789, -0.3763123456789, SolutionStatus.FLOAT), ProjectCrs.ETRS89_UTM30)

        result shouldBe CentralPointResult.Fixed(CentralPoint(39.4699123456789, -0.3763123456789))
    }

    @Test
    fun `S006 RF-34 sin posicion del receptor no se fija e indica el motivo`() {
        resolver.fromFix(null, ProjectCrs.ETRS89_UTM30) shouldBe CentralPointResult.Rejected(CentralPointRejection.NO_POSITION)
        resolver.fromFix(fix(Double.NaN, Double.NaN, SolutionStatus.NONE), ProjectCrs.ETRS89_UTM30) shouldBe
            CentralPointResult.Rejected(CentralPointRejection.NO_POSITION)
    }

    @Test
    fun `S006 RF-34 fuera del ambito del SC no se fija e indica el motivo`() {
        // Buenos Aires, un toque, una posición y unas coordenadas en geográficas.
        resolver.fromTap(-34.6, -58.4, ProjectCrs.ETRS89_UTM30) shouldBe CentralPointResult.Rejected(CentralPointRejection.OUTSIDE_CRS_AREA)
        resolver.fromFix(fix(-34.6, -58.4), ProjectCrs.ETRS89_UTM30) shouldBe
            CentralPointResult.Rejected(CentralPointRejection.OUTSIDE_CRS_AREA)
        resolver.fromTyped(EntryForm.GEOGRAPHIC, -34.6, -58.4, ProjectCrs.ETRS89_UTM30) shouldBe
            CentralPointResult.Rejected(CentralPointRejection.OUTSIDE_CRS_AREA)
    }

    @Test
    fun `S006 RF-34 unas coordenadas que no son numeros no se fijan`() {
        resolver.fromTyped(EntryForm.PROJECT_CRS, Double.NaN, 4_000_000.0, ProjectCrs.ETRS89_UTM30) shouldBe
            CentralPointResult.Rejected(CentralPointRejection.INVALID_NUMBER)
        resolver.fromTyped(EntryForm.PROJECT_CRS, 500_000.0, Double.POSITIVE_INFINITY, ProjectCrs.ETRS89_UTM30) shouldBe
            CentralPointResult.Rejected(CentralPointRejection.INVALID_NUMBER)
    }

    @Test
    fun `S006 RF-34 en ED50 fuera de la rejilla no se fija e indica el motivo`() {
        val ed50 = ProjectCrs.of(GeodeticDatum.ED50, 30).shouldNotBeNull()

        // Caso de la fixture de pyproj: dentro del ámbito del SC y fuera de la rejilla SPED2ETV2.
        resolver.fromTyped(EntryForm.GEOGRAPHIC, 35.1, -3.0, ed50) shouldBe
            CentralPointResult.Rejected(CentralPointRejection.OUTSIDE_ED50_GRID)
    }
}

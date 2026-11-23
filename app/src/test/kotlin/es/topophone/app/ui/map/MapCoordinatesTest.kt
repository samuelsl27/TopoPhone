package es.topophone.app.ui.map

import es.topophone.core.model.localization.LocalizationParams
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.PositionUnavailable
import es.topophone.core.survey.ProjectPositionCalculator
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.junit.Test

/** «Ver coordenadas»: Este y Norte frente a pyproj, la unidad del proyecto, latitud y longitud, fuera de ámbito y copiar (S036/RF-40, RF-41). */
class MapCoordinatesTest {

    private val calculator = ProjectPositionCalculator()
    private val es = Locale("es", "ES")
    private val area = VisibleArea(south = 40.0, west = -4.0, north = 41.0, east = -3.0, widthPx = 1080, heightPx = 1800)

    private fun project(crs: ProjectCrs, linear: LinearUnit = LinearUnit.METRE, angular: AngularUnit = AngularUnit.DMS) = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = crs,
        linearUnit = linear,
        angularUnit = angular,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0L,
        modifiedAtMs = 0L,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun press(lat: Double, lon: Double) = MapPress.of(lat, lon, 540f, 900f, area)

    private fun coordinates(lat: Double, lon: Double, project: Project) = MapCoordinates.of(press(lat, lon), project, calculator)

    /** Filas de datos de un vector de referencia de `fixtures/geodesia/` (sin comentarios ni cabecera), generados con pyproj. */
    private fun rows(name: String): List<List<String>> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/geodesia/$name").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
            .map { it.split(',') }
    }

    /** Compara Este y Norte con lo que dice pyproj, a 1 mm; devuelve cuántos puntos se compararon y los que difieren. */
    private fun compareWithPyproj(vectors: List<List<String>>, crsOf: (Int) -> ProjectCrs, east: Int, north: Int): Pair<Int, List<String>> {
        var compared = 0
        val failures = mutableListOf<String>()
        vectors.forEach { row ->
            val lat = row[0].toDouble()
            val lon = row[1].toDouble()
            val result = coordinates(lat, lon, project(crsOf(row[if (east == 5) 4 else 2].toInt())))
            if (result.outOfScope != null) return@forEach
            compared++
            val dE = abs(checkNotNull(result.position.easting) - row[east].toDouble())
            val dN = abs(checkNotNull(result.position.northing) - row[north].toDouble())
            if (dE > MM || dN > MM) failures += "($lat, $lon): ΔE=${dE * 1000} mm ΔN=${dN * 1000} mm"
        }
        return compared to failures
    }

    @Test
    fun `S036 RF-40 Este y Norte de ETRS89 en UTM 29, 30 y 31 coinciden con pyproj a 1 mm`() {
        val (compared, failures) = compareWithPyproj(
            rows("utm_etrs89.csv"),
            { ProjectCrs.entries.first { crs -> crs.datum.name == "ETRS89" && crs.utmZone == it } },
            east = 3,
            north = 4,
        )

        compared shouldBeGreaterThan 250
        failures shouldBe emptyList()
    }

    @Test
    fun `S036 RF-40 Este y Norte de REGCAN95 en UTM 27 y 28 coinciden con pyproj a 1 mm`() {
        val (compared, failures) = compareWithPyproj(
            rows("utm_regcan95.csv"),
            { ProjectCrs.entries.first { crs -> crs.datum.name == "REGCAN95" && crs.utmZone == it } },
            east = 3,
            north = 4,
        )

        compared shouldBeGreaterThan 40
        failures shouldBe emptyList()
    }

    @Test
    fun `S036 RF-40 Este y Norte de ED50 en UTM 29, 30 y 31 coinciden con pyproj a 1 mm`() {
        val inGrid = rows("ed50_utm.csv").filter { it[2].isNotEmpty() }
        val (compared, failures) = compareWithPyproj(
            inGrid,
            { ProjectCrs.entries.first { crs -> crs.datum.name == "ED50" && crs.utmZone == it } },
            east = 5,
            north = 6,
        )

        compared shouldBeGreaterThan 100
        failures shouldBe emptyList()
    }

    @Test
    fun `S036 RF-40 las lineas son Este, Norte, latitud y longitud con la unidad del proyecto`() {
        val row = rows("utm_etrs89.csv").first { it[2] == "30" }
        val lat = row[0].toDouble()
        val lon = row[1].toDouble()

        val lines = coordinates(lat, lon, project(ProjectCrs.ETRS89_UTM30)).lines(project(ProjectCrs.ETRS89_UTM30), "m", es)

        lines.map { it.kind } shouldBe
            listOf(CoordinateKind.EASTING, CoordinateKind.NORTHING, CoordinateKind.LATITUDE, CoordinateKind.LONGITUDE)
        lines[0].value shouldBe String.format(es, "%,.3f m", row[3].toDouble())
        lines[1].value shouldBe String.format(es, "%,.3f m", row[4].toDouble())
    }

    @Test
    fun `S036 RF-40 Este y Norte salen en los pies del proyecto con su simbolo`() {
        val row = rows("utm_etrs89.csv").first { it[2] == "30" }
        val feet = project(ProjectCrs.ETRS89_UTM30, linear = LinearUnit.INTERNATIONAL_FOOT)

        val lines = coordinates(row[0].toDouble(), row[1].toDouble(), feet).lines(feet, "ft", es)

        lines[0].value shouldBe String.format(es, "%,.3f ft", row[3].toDouble() / FOOT_M)
        lines[1].value shouldBe String.format(es, "%,.3f ft", row[4].toDouble() / FOOT_M)
    }

    @Test
    fun `S036 RF-40 la latitud y la longitud van en la unidad angular del proyecto`() {
        val dms = coordinates(40.4167754, -3.7037902, project(ProjectCrs.ETRS89_UTM30, angular = AngularUnit.DMS))
            .lines(project(ProjectCrs.ETRS89_UTM30, angular = AngularUnit.DMS), "m", es)
        val degrees = coordinates(40.4167754, -3.7037902, project(ProjectCrs.ETRS89_UTM30, angular = AngularUnit.DECIMAL_DEGREES))
            .lines(project(ProjectCrs.ETRS89_UTM30, angular = AngularUnit.DECIMAL_DEGREES), "m", es)

        dms[2].value.startsWith("40° 25′") shouldBe true
        dms[2].value.endsWith("N") shouldBe true
        dms[3].value.startsWith("003° 42′") shouldBe true
        dms[3].value.endsWith("W") shouldBe true
        degrees[2].value.startsWith("40,41677") shouldBe true
        degrees[3].value.startsWith("-3,70379") shouldBe true
    }

    @Test
    fun `S036 RF-41 un punto fuera del ambito del SC solo da latitud y longitud y el motivo`() {
        val utm30 = project(ProjectCrs.ETRS89_UTM30)

        val result = coordinates(28.1, -15.4, utm30)

        result.outOfScope shouldBe PositionUnavailable.OUTSIDE_CRS_AREA
        result.lines(utm30, "m", es).map { it.kind } shouldBe listOf(CoordinateKind.LATITUDE, CoordinateKind.LONGITUDE)
        result.latitudeDeg shouldBe 28.1
        result.longitudeDeg shouldBe -15.4
    }

    @Test
    fun `S036 RF-41 lo mismo en un proyecto de Canarias con un punto de la Peninsula`() {
        val canarias = project(ProjectCrs.REGCAN95_UTM28)

        val result = coordinates(40.4, -3.7, canarias)

        result.outOfScope shouldBe PositionUnavailable.OUTSIDE_CRS_AREA
        result.lines(canarias, "m", es).size shouldBe 2
    }

    @Test
    fun `S036 RF-40 un punto dentro del ambito no tiene motivo de fuera de ambito`() {
        coordinates(40.4, -3.7, project(ProjectCrs.ETRS89_UTM30)).outOfScope shouldBe null
        coordinates(40.4, -3.7, project(ProjectCrs.ED50_UTM30)).outOfScope shouldBe null
    }

    @Test
    fun `S036 RF-40 en un SC geografico salen la latitud y la longitud de su datum y no Este ni Norte`() {
        val geographic = project(ProjectCrs.ETRS89_GEOGRAPHIC)

        val lines = coordinates(40.4167754, -3.7037902, geographic).lines(geographic, "m", es)

        lines.map { it.kind } shouldBe listOf(CoordinateKind.LATITUDE, CoordinateKind.LONGITUDE)
    }

    @Test
    fun `S036 RF-40 Copiar deja las mismas lineas que se ven, una por linea con su nombre`() {
        val utm30 = project(ProjectCrs.ETRS89_UTM30)
        val lines = coordinates(40.4167754, -3.7037902, utm30).lines(utm30, "m", es)
        val names = mapOf(
            CoordinateKind.EASTING to "Este (E)",
            CoordinateKind.NORTHING to "Norte (N)",
            CoordinateKind.LATITUDE to "Latitud",
            CoordinateKind.LONGITUDE to "Longitud",
        )

        val text = MapCoordinates.copyText(lines) { names.getValue(it) }

        text.lines() shouldBe lines.map { "${names.getValue(it.kind)}: ${it.value}" }
        text.lines().size shouldBe 4
    }

    private companion object {
        const val MM = 0.001
        const val FOOT_M = 0.3048
    }

    @Test
    fun `S039 RF-17 con localizacion activa las coordenadas del punto marcado son las localizadas`() {
        val project = project(ProjectCrs.ETRS89_UTM30)
        val plain = coordinates(40.5, -3.5, project)
        val params = LocalizationParams(
            centreE = plain.position.easting!!,
            centreN = plain.position.northing!!,
            knownCentreE = plain.position.easting!! + 100.0,
            knownCentreN = plain.position.northing!! - 50.0,
            hasHorizontal = true,
        )

        val localized = MapCoordinates.of(press(40.5, -3.5), project, calculator, params)

        localized.localized shouldBe true
        plain.localized shouldBe false
        localized.position.easting!! shouldBe (plain.position.easting!! + 100.0)
        localized.position.northing!! shouldBe (plain.position.northing!! - 50.0)
        localized.position.latitudeDeg shouldBe plain.position.latitudeDeg
    }
}

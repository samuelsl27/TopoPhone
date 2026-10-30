package es.topophone.core.survey

import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.ProjectCrs
import java.io.File

/** Los vectores de `fixtures/base_shift/` (S020/T1), generados con pyproj. Las alturas son elipsoidales. */
object BaseShiftFixtures {

    /** Una fila de `vectores.csv`: el conocido, la medida, el vector y lo esperado. */
    data class Vector(
        val name: String,
        val crs: ProjectCrs,
        val knownEasting: Double,
        val knownNorthing: Double,
        val knownHeight: Double?,
        val measuredLat: Double,
        val measuredLon: Double,
        val measuredHeight: Double,
        val dx: Double,
        val dy: Double,
        val dz: Double,
        val deltaEast: Double,
        val deltaNorth: Double,
        val deltaHeight: Double,
        val module: Double,
        val classification: String,
    )

    /** Una fila de `aplicados.csv`: una punta del suelo, el vector sumado y las coordenadas esperadas. */
    data class Applied(
        val name: String,
        val crs: ProjectCrs,
        val lat: Double,
        val lon: Double,
        val height: Double,
        val dx: Double,
        val dy: Double,
        val dz: Double,
        val easting: Double,
        val northing: Double,
        val projectHeight: Double,
    )

    private fun lines(name: String): List<List<String>> {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        return File(root, "fixtures/base_shift/$name").readLines(Charsets.UTF_8)
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .drop(1)
            .map { it.split(',') }
    }

    private fun crsOf(datum: String, zone: String): ProjectCrs = ProjectCrs.of(GeodeticDatum.valueOf(datum), zone.toInt())!!

    val vectors: List<Vector> by lazy {
        lines("vectores.csv").map { c ->
            Vector(
                name = c[0],
                crs = crsOf(c[1], c[3]),
                knownEasting = c[4].toDouble(),
                knownNorthing = c[5].toDouble(),
                knownHeight = c[6].takeIf { it.isNotEmpty() }?.toDouble(),
                measuredLat = c[7].toDouble(),
                measuredLon = c[8].toDouble(),
                measuredHeight = c[9].toDouble(),
                dx = c[10].toDouble(),
                dy = c[11].toDouble(),
                dz = c[12].toDouble(),
                deltaEast = c[13].toDouble(),
                deltaNorth = c[14].toDouble(),
                deltaHeight = c[15].toDouble(),
                module = c[16].toDouble(),
                classification = c[17],
            )
        }
    }

    val applied: List<Applied> by lazy {
        val byName = vectors.associateBy { it.name }
        lines("aplicados.csv").map { c ->
            Applied(
                name = c[0],
                crs = byName.getValue(c[0]).crs,
                lat = c[2].toDouble(),
                lon = c[3].toDouble(),
                height = c[4].toDouble(),
                dx = c[5].toDouble(),
                dy = c[6].toDouble(),
                dz = c[7].toDouble(),
                easting = c[8].toDouble(),
                northing = c[9].toDouble(),
                projectHeight = c[10].toDouble(),
            )
        }
    }
}

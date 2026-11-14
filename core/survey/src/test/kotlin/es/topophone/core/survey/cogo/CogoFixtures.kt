package es.topophone.core.survey.cogo

import es.topophone.core.model.project.ProjectCrs
import java.io.File
import kotlin.math.PI
import kotlin.math.abs

/** Los CSV de `fixtures/cogo/` calculados con pyproj y aparte (constitución nº 2). */
object CogoFixtures {
    /** 1 mm y 0,001″ (S038, constitución nº 2). */
    const val LENGTH_TOLERANCE_M = 1e-3
    const val ANGLE_TOLERANCE_RAD = 4.85e-9

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    private val cache = mutableMapOf<String, List<Map<String, String>>>()

    fun rows(name: String): List<Map<String, String>> = cache.getOrPut(name) {
        val lines = File(root, "fixtures/cogo/$name").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val header = lines.first().split(",")
        lines.drop(1).map { line -> header.zip(line.split(",", limit = header.size)).toMap() }
    }

    fun crs(row: Map<String, String>): ProjectCrs = ProjectCrs.valueOf(row.getValue("crs"))

    /** Los puntos de [crs] por su identificador (P1 a P8 y R1 a R12). */
    fun points(crs: ProjectCrs): Map<String, CogoPoint> = rows("puntos.csv").filter { it.getValue("crs") == crs.name }.associate {
        it.getValue("id") to CogoPoint(
            easting = it.number("este"),
            northing = it.number("norte"),
            height = it.optional("cota"),
            latitudeDeg = it.number("latitud"),
            longitudeDeg = it.number("longitud"),
            ellipsoidalHeightM = it.optional("altura_elipsoidal"),
        )
    }

    fun Map<String, String>.number(key: String) = getValue(key).toDouble()

    fun Map<String, String>.optional(key: String) = getValue(key).takeIf { it.isNotEmpty() }?.toDouble()

    /** La diferencia entre dos ángulos en radianes, de 0 a π, sin importar las vueltas. */
    fun angleDiff(a: Double, b: Double): Double {
        val twoPi = 2 * PI
        val d = ((a - b) % twoPi + twoPi) % twoPi
        return if (d > PI) twoPi - d else d
    }

    fun close(a: Double, b: Double, tolerance: Double) = abs(a - b) <= tolerance
}

package es.topophone.core.survey.geometry

import java.io.File

/** Los CSV de `fixtures/geometria/` calculados con pyproj y aparte (constitución nº 2). */
object GeometryFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun rows(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/geometria/$name").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",", limit = header.size)).toMap() }
    }

    /** Los vértices de un caso. */
    fun vertices(case: String): List<GeometryVertex> = rows("vertices.csv").filter { it.getValue("caso") == case }.map {
        GeometryVertex(
            easting = it.optional("este"),
            northing = it.optional("norte"),
            height = it.optional("cota"),
            latitudeDeg = it.optional("latitud"),
            longitudeDeg = it.optional("longitud"),
            ellipsoidalHeightM = it.optional("altura_elipsoidal"),
        )
    }

    fun closed(case: String): Boolean = rows("vertices.csv").first { it.getValue("caso") == case }.getValue("cerrada") == "1"

    fun segments(case: String, mode: String): List<Map<String, String>> =
        rows("segmentos.csv").filter { it.getValue("caso") == case && it.getValue("modo") == mode }

    fun total(case: String, mode: String): Map<String, String> =
        rows("totales.csv").first { it.getValue("caso") == case && it.getValue("modo") == mode }

    fun angle(case: String, mode: String): Map<String, String> =
        rows("angulos.csv").first { it.getValue("caso") == case && it.getValue("modo") == mode }

    fun Map<String, String>.number(key: String) = getValue(key).toDouble()

    fun Map<String, String>.optional(key: String) = getValue(key).takeIf { it.isNotEmpty() }?.toDouble()
}

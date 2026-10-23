package es.topophone.core.survey

import java.io.File

/** Filas de un CSV de `fixtures/geodesia`, sin comentarios ni cabecera (vectores de pyproj, constitución nº 2). */
object SurveyFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun rows(name: String): List<List<String>> = File(root, "fixtures/geodesia/$name").readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .drop(1)
        .map { it.split(",") }
}

package es.topophone.core.geodesy

import java.io.File

/** Vectores de referencia de `fixtures/geodesia/` generados con pyproj (`tools/geodesia/generar_vectores.py`). */
object GeodesyFixtures {

    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
            ?: error("No se encuentra la raíz del repositorio")
    }

    /** Nombres de los CSV de `fixtures/geodesia/` que empiezan por [prefix], ordenados. */
    fun names(prefix: String): List<String> =
        File(root, "fixtures/geodesia").listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".csv") }!!.map { it.name }.sorted()

    /** Filas de datos (sin comentarios ni cabecera), con los campos separados. */
    fun rows(name: String): List<List<String>> = File(root, "fixtures/geodesia/$name")
        .readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .drop(1)
        .map { it.split(',') }
}

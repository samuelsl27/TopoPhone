package es.topophone.core.geodesy

import java.io.File

/** Filas de `fixtures/mapas_offline/` generadas por `tools/mapas_offline/generar_teselas.py` (S037/T3), con su cabecera como claves. */
internal object OfflineFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
            ?: error("No se encuentra la raíz del repositorio")
    }

    fun rows(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/mapas_offline/$name").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val header = lines.first().split(',')
        return lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
    }
}

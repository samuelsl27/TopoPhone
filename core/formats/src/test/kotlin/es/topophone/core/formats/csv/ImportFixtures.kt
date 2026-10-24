package es.topophone.core.formats.csv

import java.io.File

/** Los ficheros de ejemplo de `fixtures/formatos/csv-importacion/` y los CSV que escribe S012, byte a byte (S028). */
object ImportFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    private val folder: File get() = File(root, "fixtures/formatos/csv-importacion")

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    /** Los ficheros de ejemplo, sin `esperado.csv`. */
    val samples: List<String> by lazy { folder.listFiles().orEmpty().map { it.name }.filter { it != "esperado.csv" }.sorted() }

    /** Un CSV de S012 ya escrito por la app: `fixtures/formatos/csv/`. */
    fun s012(name: String): ByteArray = File(root, "fixtures/formatos/csv/$name").readBytes()
}

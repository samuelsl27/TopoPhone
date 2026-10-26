package es.topophone.app.ui.surface

import es.topophone.app.ui.points.ImportSource
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Los LandXML de ejemplo de `fixtures/superficies/` como fuentes de la importación, sin pasar por el selector de ficheros (S042). */
object SurfaceTestFiles {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun bytes(name: String): ByteArray = File(root, "fixtures/superficies/$name").readBytes()

    /** Una fuente que sirve los bytes del fichero [name] cada vez que se abre; [declaredSize] puede mentir o ser null. */
    fun source(name: String, declaredSize: Long? = bytes(name).size.toLong()): ImportSource {
        val data = bytes(name)
        return ImportSource(name, declaredSize) { ByteArrayInputStream(data) }
    }

    /** Una fuente con ese contenido, de nombre [name]. */
    fun source(name: String, text: String): ImportSource {
        val data = text.toByteArray()
        return ImportSource(name, data.size.toLong()) { ByteArrayInputStream(data) }
    }
}

/** Espera a que el fichero esté leído y explorado: Room contesta desde otro hilo. Un test no debe acabar con la carga en vuelo. */
fun SurfaceImportViewModel.settled(): SurfaceImportUiState = runBlocking {
    withTimeout(10_000) {
        state.first { it.stage != SurfaceImportStage.LOADING && it.stage != SurfaceImportStage.IMPORTING && !it.scanning }
    }
}

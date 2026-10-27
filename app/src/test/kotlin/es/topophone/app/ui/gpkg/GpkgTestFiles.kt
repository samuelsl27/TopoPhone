package es.topophone.app.ui.gpkg

import es.topophone.app.ui.points.ImportSource
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Los GeoPackage de ejemplo de `fixtures/formatos/gpkg/` como fuentes de la importación, sin pasar por el selector de ficheros (S048). */
object GpkgTestFiles {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun bytes(name: String): ByteArray = File(root, "fixtures/formatos/gpkg/$name").readBytes()

    /** Una fuente que sirve los bytes del fichero [name] cada vez que se abre; [declaredSize] puede mentir o ser null. */
    fun source(name: String, declaredSize: Long? = bytes(name).size.toLong()): ImportSource {
        val data = bytes(name)
        return ImportSource(name, declaredSize) { ByteArrayInputStream(data) }
    }
}

/** Espera a que el fichero esté leído: Room y SQLite contestan desde otro hilo. Un test no debe acabar con la carga en vuelo. */
fun GpkgImportViewModel.settled(): GpkgImportUiState = runBlocking {
    withTimeout(10_000) {
        state.first { it.stage != GpkgImportStage.LOADING && it.stage != GpkgImportStage.IMPORTING }
    }
}

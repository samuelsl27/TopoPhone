package es.topophone.app.ui.gpkg

import androidx.lifecycle.viewModelScope
import es.topophone.app.ui.map.FakeMapPreferences
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.app.ui.points.ImportSource
import es.topophone.app.ui.projects.ProjectCenterProvider
import es.topophone.core.model.log.AppLog
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.data.cad.CadRepository
import es.topophone.data.gpkg.GeoPackageImporter
import es.topophone.data.gpkg.GeoPackageReader
import es.topophone.data.kml.KmlReader
import es.topophone.data.map.MapViewportRepository
import es.topophone.data.project.ProjectRepository
import es.topophone.data.shp.ShapefileReader
import es.topophone.receiver.session.ReceiverSession
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /** Las filas de un CSV de lo esperado, sin los comentarios, como mapas columna → valor (sin comillas ni saltos dentro de las celdas). */
    fun expected(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/formatos/gpkg/$name").readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    /** Una fuente que sirve los bytes del fichero [name] cada vez que se abre; [declaredSize] puede mentir o ser null. */
    fun source(name: String, declaredSize: Long? = bytes(name).size.toLong()): ImportSource {
        val data = bytes(name)
        return ImportSource(name, declaredSize) { ByteArrayInputStream(data) }
    }
}

/** Los Shapefiles de ejemplo de `fixtures/formatos/shp/` como fuentes de la importación, sin pasar por el selector de ficheros (S030). */
object ShpTestFiles {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun file(path: String): File = File(root, "fixtures/formatos/shp/$path")

    /** Las filas de un CSV de lo esperado, sin los comentarios, como mapas columna → valor (sin comillas ni saltos dentro de las celdas). */
    fun expected(name: String): List<Map<String, String>> {
        val lines = file(name).readText().lines().filter { it.isNotEmpty() && !it.startsWith("#") }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    /** Una fuente que sirve los bytes del fichero cada vez que se abre; [declaredSize] puede mentir o ser null. */
    fun source(path: String, declaredSize: Long? = file(path).length()): ImportSource {
        val data = file(path).readBytes()
        return ImportSource(file(path).name, declaredSize) { ByteArrayInputStream(data) }
    }

    fun sources(vararg paths: String): List<ImportSource> = paths.map { source(it) }
}

/** Los KML y KMZ de ejemplo de `fixtures/formatos/kml/` como fuentes de la importación, sin pasar por el selector de ficheros (S031). */
object KmlTestFiles {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun file(path: String): File = File(root, "fixtures/formatos/kml/$path")

    /** Una fuente que sirve los bytes del fichero cada vez que se abre; [declaredSize] puede mentir o ser null. */
    fun source(path: String, declaredSize: Long? = file(path).length()): ImportSource {
        val data = file(path).readBytes()
        return ImportSource(file(path).name, declaredSize) { ByteArrayInputStream(data) }
    }
}

/** Un receptor sin conexión, que no da posición. */
fun idleReceiver(): ReceiverSession =
    ReceiverSession(CoroutineScope(SupervisorJob() + Dispatchers.IO), NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE })

private val created = java.util.concurrent.ConcurrentLinkedQueue<GpkgImportViewModel>()

/** Cancela el trabajo en vuelo de los ViewModel que creó el test y cierra sus ficheros, como hace el ciclo de vida al salir de la pantalla. */
fun disposeGpkgViewModels() {
    while (true) {
        val viewModel = created.poll() ?: break
        viewModel.viewModelScope.cancel()
        val onCleared = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(viewModel)
    }
}

/** El ViewModel de la importación con sus dependencias de prueba; sin espera para recontar la zona. */
fun gpkgViewModel(
    cache: File,
    projects: ProjectRepository,
    viewports: MapViewportRepository,
    dispatcher: CoroutineDispatcher,
    cad: CadRepository,
    receiver: ReceiverSession = idleReceiver(),
    log: AppLog = AppLog.NONE,
): GpkgImportViewModel = GpkgImportViewModel(
    GeoPackageReader(cache),
    ShapefileReader(File(cache, "shp")),
    KmlReader(File(cache, "kml")),
    GeoPackageImporter(cad),
    projects,
    ProjectCenterProvider(FakePointRepository(), viewports),
    receiver,
    FakeMapPreferences(),
    dispatcher,
    log,
).also {
    it.debounceMs = 0
    created += it
}

/** Espera a que el fichero esté leído y contado: Room y SQLite contestan desde otro hilo. Un test no debe acabar con la carga en vuelo. */
fun GpkgImportViewModel.settled(): GpkgImportUiState = runBlocking {
    withTimeout(10_000) {
        state.first {
            it.stage != GpkgImportStage.LOADING && it.stage != GpkgImportStage.IMPORTING && !it.scanning && !it.zone.counting
        }
    }
}

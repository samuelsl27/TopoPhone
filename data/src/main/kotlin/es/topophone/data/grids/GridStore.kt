package es.topophone.data.grids

import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GeoidFromGrid
import es.topophone.core.geodesy.catalog.GeoidSource
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.GridSource
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.Project
import es.topophone.core.survey.BundledLibraryResources
import es.topophone.core.survey.LibraryResources
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Dónde está una rejilla: en la app, descargada o sin descargar (S034/RF-12, RF-17, RF-18). */
sealed interface GridState {
    /** Viene en el APK (la SPED2ETV2 y los EGM08-REDNAP, S034/RF-17). */
    data object Bundled : GridState

    data class Installed(val sizeBytes: Long) : GridState

    data object Missing : GridState
}

/** Una rejilla descargada con lo que ocupa y los proyectos que la usan (S034/RF-23). */
data class InstalledGrid(val entry: GridEntry, val sizeBytes: Long, val usedBy: List<String>) {
    val inUse: Boolean get() = usedBy.isNotEmpty()
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult

    /** Un proyecto la usa: no se borra (S034/RF-23). */
    data class InUse(val projectNames: List<String>) : DeleteResult

    data object NotInstalled : DeleteResult
}

/**
 * Las rejillas descargadas, en [directory] (el almacenamiento privado de la app), y de dónde se leen las rejillas y los geoides de los SC de la
 * biblioteca: lo que viene en la app y lo instalado (S034/RF-12, RF-17, RF-23, RF-24). Cada vez que se instala o se borra una, [generation] sube
 * para que los motores de cálculo se rehagan. Una rejilla a medias (`.part`) no se lee nunca.
 *
 * @param activeProjects los proyectos que no están en la papelera, para saber qué rejillas usan.
 */
class GridStore(
    private val directory: File,
    private val catalog: CrsCatalog = CrsCatalog.bundled,
    private val activeProjects: suspend () -> List<Project>,
) : LibraryResources {
    private val counter = AtomicInteger(1)
    private val geoids = HashMap<String, GeoidFromGrid>()
    private val classpath = ClasspathGridSource("/shift/")

    override val generation: Int get() = counter.get()

    override val grids: GridSource = GridSource { name -> if (isBundled(name)) classpath.read(name) else installedFile(name)?.readBytes() }

    @Synchronized
    override fun geoid(gridName: String): GeoidSource? {
        if (isBundled(gridName)) return BundledLibraryResources.geoid(gridName)
        val file = installedFile(gridName) ?: return null
        val entry = catalog.grids[gridName]
        return geoids.getOrPut(gridName) {
            GeoidFromGrid(
                gridName,
                entry?.let {
                    "${it.source}, ${it.license.name}"
                }.orEmpty(),
            ) { file.takeIf { f -> f.exists() }?.readBytes() }
        }
    }

    private fun isBundled(name: String): Boolean = catalog.grids[name]?.bundled == true

    /** El fichero de una rejilla instalada, o `null` si no lo está o el nombre intenta salir de la carpeta. */
    private fun installedFile(name: String): File? {
        val file = fileOf(name) ?: return null
        return file.takeIf { it.isFile }
    }

    /** El fichero que tendría la rejilla [name]; `null` si el nombre no es un nombre de fichero sin carpetas. */
    fun fileOf(name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.startsWith(".") || name.endsWith(PART_SUFFIX)) return null
        return File(directory, name)
    }

    /** El fichero donde se descarga la rejilla antes de comprobar su SHA-256 (S034/RF-21, RF-22). */
    fun partFileOf(name: String): File? = fileOf(name)?.let { File(directory, name + PART_SUFFIX) }

    fun state(name: String): GridState = when {
        isBundled(name) -> GridState.Bundled
        else -> installedFile(name)?.let { GridState.Installed(it.length()) } ?: GridState.Missing
    }

    /** Las rejillas descargadas, con su tamaño y los proyectos que las usan, por nombre. */
    suspend fun installedGrids(): List<InstalledGrid> {
        val files = directory.listFiles { f -> f.isFile && !f.name.endsWith(PART_SUFFIX) }?.sortedBy { it.name }.orEmpty()
        val projects = activeProjects()
        return files.mapNotNull { file ->
            val entry = catalog.grids[file.name] ?: return@mapNotNull null
            InstalledGrid(entry, file.length(), usedBy(file.name, projects))
        }
    }

    /** Borra una rejilla descargada si ningún proyecto la usa (S034/RF-23). */
    suspend fun delete(name: String): DeleteResult {
        val file = installedFile(name) ?: return DeleteResult.NotInstalled
        val users = usedBy(name, activeProjects())
        if (users.isNotEmpty()) return DeleteResult.InUse(users)
        file.delete()
        forget(name)
        return DeleteResult.Deleted
    }

    /** Anota que la rejilla [name] ya está instalada o ya no lo está, para que los motores se rehagan. */
    @Synchronized
    fun changed(name: String) {
        geoids.remove(name)
        counter.incrementAndGet()
    }

    private fun forget(name: String) = changed(name)

    /** Los proyectos cuyo SC de la biblioteca usa la rejilla [name] en su transformación o como su geoide. */
    fun usedBy(name: String, projects: List<Project>): List<String> = projects.filter { project ->
        val crs = project.crs as? LibraryCrs ?: return@filter false
        name in crs.definition.gridNames || crs.geoidGrid == name
    }.map { it.name }

    companion object {
        const val PART_SUFFIX = ".part"
    }
}

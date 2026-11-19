package es.topophone.buildlogic.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Falla si una variante `standard` lleva el módulo experimental o algo de su flavor (S044/RF-1). */
@CacheableTask
abstract class CheckNoExperimentalTask : DefaultTask() {

    /** Rutas de los módulos del proyecto en el classpath de ejecución de la variante. */
    @get:Input
    abstract val projectPaths: ListProperty<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations = ExperimentalGuard.findViolations(projectPaths.get(), mergedManifest.get().asFile.readText())
        val text = if (violations.isEmpty()) "Sin nada del flavor experimental.\n" else violations.joinToString("\n", postfix = "\n")
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
        if (violations.isNotEmpty()) {
            throw GradleException("La variante standard lleva el flavor experimental (S044/RF-1):\n$text")
        }
    }

    companion object {
        /** Recorre el grafo resuelto y devuelve la ruta de todos los módulos del proyecto. */
        fun collectProjectPaths(root: ResolvedComponentResult): List<String> {
            val seen = mutableSetOf<ResolvedComponentResult>()
            val pending = ArrayDeque(listOf(root))
            val result = sortedSetOf<String>()
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (!seen.add(component)) continue
                (component.id as? ProjectComponentIdentifier)?.let { result += it.projectPath }
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending += it.selected }
            }
            return result.toList()
        }
    }
}

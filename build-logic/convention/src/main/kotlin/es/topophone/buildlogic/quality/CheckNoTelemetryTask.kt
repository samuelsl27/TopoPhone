package es.topophone.buildlogic.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Falla si el classpath de ejecución de la variante release contiene un SDK de telemetría (S001/RF-24). */
@CacheableTask
abstract class CheckNoTelemetryTask : DefaultTask() {

    /** Dependencias resueltas (directas e indirectas) como `grupo:artefacto:versión`. */
    @get:Input
    abstract val coordinates: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val forbidden = TelemetryGuard.findForbidden(coordinates.get())
        val text = if (forbidden.isEmpty()) "Sin SDK de telemetría.\n" else forbidden.joinToString("\n", postfix = "\n")
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
        if (forbidden.isNotEmpty()) {
            throw GradleException("SDK de telemetría prohibidos en el APK (S001/RF-24):\n$text")
        }
    }

    companion object {
        /** Recorre el grafo resuelto y devuelve todos los módulos externos. */
        fun collectCoordinates(root: ResolvedComponentResult): List<String> {
            val seen = mutableSetOf<ResolvedComponentResult>()
            val pending = ArrayDeque(listOf(root))
            val result = sortedSetOf<String>()
            while (pending.isNotEmpty()) {
                val component = pending.removeFirst()
                if (!seen.add(component)) continue
                (component.id as? ModuleComponentIdentifier)?.let { result += "${it.group}:${it.module}:${it.version}" }
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { pending += it.selected }
            }
            return result.toList()
        }
    }
}

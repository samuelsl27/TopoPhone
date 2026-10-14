package es.topophone.buildlogic.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.RegularFileProperty

/** Comprueba las reglas de módulos e imports (S001/RF-27) y falla nombrando cada infracción. */
@CacheableTask
abstract class CheckModuleRulesTask : DefaultTask() {

    @get:Input
    abstract val modules: ListProperty<ModuleInfo>

    /** Fuentes de los módulos de dominio, agrupadas por módulo: `ruta-del-módulo` → ficheros. */
    @get:Input
    abstract val domainSourceRoots: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val domainSources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val sourcesByModule = domainSourceRoots.get().associate { entry ->
            val (modulePath, dir) = entry.split("=", limit = 2)
            modulePath to root.resolve(dir)
        }
        val importViolations = sourcesByModule.flatMap { (modulePath, dir) ->
            val files = domainSources.files.filter { it.startsWith(dir) }
            ModuleRules.checkImports(
                modulePath,
                files.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() },
            )
        }
        val violations = ModuleRules.checkModules(modules.get()) + importViolations

        val text = if (violations.isEmpty()) "Sin infracciones.\n" else violations.joinToString("\n", postfix = "\n") { it.describe() }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)

        if (violations.isNotEmpty()) {
            throw GradleException("Reglas de módulos incumplidas (S001/RF-27):\n" + text)
        }
    }
}

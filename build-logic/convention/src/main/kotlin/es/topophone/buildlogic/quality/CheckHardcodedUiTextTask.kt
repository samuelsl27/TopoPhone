package es.topophone.buildlogic.quality

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Falla si el código Compose de un módulo contiene textos visibles literales (S001/RF-19). */
@CacheableTask
abstract class CheckHardcodedUiTextTask : DefaultTask() {

    @get:Input
    abstract val modulePath: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val root = rootDirectory.get().asFile
        val findings = UiTextRules.findHardcoded(
            sources.files.associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() },
            module = modulePath.get(),
        )
        val text = if (findings.isEmpty()) "Sin textos literales.\n" else findings.joinToString("\n", postfix = "\n") { it.describe() }
        report.get().asFile.apply { parentFile.mkdirs() }.writeText(text)
        if (findings.isNotEmpty()) {
            throw GradleException("Textos visibles escritos en el código (S001/RF-19):\n$text")
        }
    }
}

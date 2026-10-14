package es.topophone.buildlogic

import es.topophone.buildlogic.quality.CheckModuleRulesTask
import es.topophone.buildlogic.quality.ModuleInfo
import es.topophone.buildlogic.quality.ModuleRules
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.kotlin.dsl.register

/**
 * Comprobaciones de calidad de todo el repositorio. Se aplica solo al proyecto raíz
 * y engancha sus tareas a `./gradlew check`.
 */
class QualityConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        require(target == target.rootProject) { "topophone.quality se aplica solo al proyecto raíz" }
        target.pluginManager.apply("base")

        val checkModuleRules = target.tasks.register<CheckModuleRulesTask>("checkModuleRules") {
            group = "verification"
            description = "Comprueba el grafo de módulos y los imports prohibidos en el dominio (S001/RF-27)."
            rootDirectory.set(target.layout.projectDirectory)
            report.set(target.layout.buildDirectory.file("reports/quality/module-rules.txt"))
        }

        target.gradle.projectsEvaluated {
            val subprojects = target.subprojects.toList()
            val domain = subprojects.filter { ModuleRules.isDomainModule(it.path) }
            checkModuleRules.configure {
                modules.set(subprojects.map { it.toModuleInfo() })
                domainSourceRoots.set(
                    domain.map { "${it.path}=${it.projectDir.resolve("src/main").relativeTo(target.rootDir).invariantSeparatorsPath}" },
                )
                domainSources.from(domain.map { p -> p.fileTree("src/main") { include("**/*.kt", "**/*.java") } })
            }
        }

        target.tasks.named("check") { dependsOn(checkModuleRules) }
    }

    private fun Project.toModuleInfo(): ModuleInfo = ModuleInfo(
        path = path,
        isAndroid = ANDROID_PLUGINS.any(pluginManager::hasPlugin),
        appliedPlugins = INSPECTED_PLUGINS.filter(pluginManager::hasPlugin).toSet(),
        projectDependencies = configurations
            .filter { isProductionConfiguration(it.name) }
            .flatMap { configuration -> configuration.dependencies.withType(ProjectDependency::class.java).map { it.path } }
            .toSet(),
    )

    private fun isProductionConfiguration(name: String): Boolean {
        if (name.startsWith("test") || name.startsWith("androidTest") || "UnitTest" in name || "AndroidTest" in name) return false
        return PRODUCTION_SUFFIXES.any { name == it.replaceFirstChar(Char::lowercase) || name.endsWith(it) }
    }

    private companion object {
        val ANDROID_PLUGINS =
            listOf("com.android.application", "com.android.library", "com.android.test", "com.android.kotlin.multiplatform.library")
        val INSPECTED_PLUGINS = listOf("com.google.dagger.hilt.android", "com.google.devtools.ksp", "org.jetbrains.kotlin.android")
        val PRODUCTION_SUFFIXES = listOf("Api", "Implementation", "CompileOnly", "RuntimeOnly", "CompileOnlyApi")
    }
}

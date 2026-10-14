package es.topophone.buildlogic.quality

import java.io.Serializable

/** Lo que las reglas necesitan saber de un módulo, ya extraído del proyecto Gradle. */
data class ModuleInfo(
    val path: String,
    val isAndroid: Boolean,
    val appliedPlugins: Set<String>,
    val projectDependencies: Set<String>,
) : Serializable

data class ModuleRuleViolation(val module: String, val message: String, val location: String? = null) {
    fun describe(): String = "[$module] " + (location?.let { "$it: " } ?: "") + message
}

/**
 * Reglas de dependencia de `docs/arquitectura.md` (S001/RF-27, constitución nº 6):
 * la lógica de dominio es Kotlin puro, sin Android ni Hilt, y respeta el grafo permitido.
 */
object ModuleRules {

    /** Módulos de dominio: JVM puro. `core:designsystem` es Android a propósito (tema y componentes). */
    private val DOMAIN_ALLOWED_DEPENDENCIES: Map<String, Set<String>> = mapOf(
        ":core:model" to emptySet(),
        ":core:ntrip" to emptySet(),
        ":core:geodesy" to setOf(":core:model"),
        ":core:gnss" to setOf(":core:model"),
        ":core:formats" to setOf(":core:model", ":core:geodesy"),
        ":core:survey" to setOf(":core:model", ":core:geodesy", ":core:gnss"),
        ":core:testing" to setOf(":core:model", ":core:geodesy", ":core:gnss", ":core:ntrip", ":core:survey", ":core:formats"),
    )

    private val ANDROID_CORE_ALLOWED_DEPENDENCIES: Map<String, Set<String>> = mapOf(
        ":core:designsystem" to setOf(":core:model"),
    )

    private val FORBIDDEN_DOMAIN_PLUGINS = setOf(
        "com.google.dagger.hilt.android",
        "com.google.devtools.ksp",
        "org.jetbrains.kotlin.android",
    )

    private val FORBIDDEN_DOMAIN_IMPORT_PREFIXES = listOf("android.", "androidx.", "dagger.", "javax.inject.", "com.google.dagger.")

    private const val APP = ":app"
    private const val EXPERIMENTAL = ":receiver-experimental"
    private val NON_CORE_FORBIDDEN_FOR_CORE = setOf(APP, ":data", ":receiver", EXPERIMENTAL)

    private val IMPORT_LINE = Regex("""^\s*import\s+([\w.]+)""")

    fun isDomainModule(path: String): Boolean = path in DOMAIN_ALLOWED_DEPENDENCIES

    fun checkModules(modules: List<ModuleInfo>): List<ModuleRuleViolation> = modules.flatMap(::checkModule)

    private fun checkModule(module: ModuleInfo): List<ModuleRuleViolation> = buildList {
        val path = module.path
        val deps = module.projectDependencies

        if (APP in deps) add(ModuleRuleViolation(path, "ningún módulo puede depender de $APP"))
        if (EXPERIMENTAL in deps && path != APP) {
            add(ModuleRuleViolation(path, "solo $APP puede depender de $EXPERIMENTAL (ADR-18)"))
        }
        if (!path.startsWith(":core:")) return@buildList

        val allowed = DOMAIN_ALLOWED_DEPENDENCIES[path] ?: ANDROID_CORE_ALLOWED_DEPENDENCIES[path]
        if (allowed == null) {
            add(ModuleRuleViolation(path, "módulo core sin regla de dependencias en docs/arquitectura.md"))
            return@buildList
        }
        deps.filter { it in NON_CORE_FORBIDDEN_FOR_CORE && it != APP }.forEach {
            add(ModuleRuleViolation(path, "un módulo core no puede depender de $it"))
        }
        (deps - allowed - NON_CORE_FORBIDDEN_FOR_CORE).sorted().forEach {
            add(ModuleRuleViolation(path, "dependencia no permitida con $it (permitidas: ${allowed.sorted().ifEmpty { listOf("ninguna") }.joinToString()})"))
        }
        if (isDomainModule(path)) {
            if (module.isAndroid) add(ModuleRuleViolation(path, "un módulo de dominio no puede usar el plugin de Android"))
            (module.appliedPlugins intersect FORBIDDEN_DOMAIN_PLUGINS).sorted().forEach {
                add(ModuleRuleViolation(path, "un módulo de dominio no puede aplicar el plugin $it"))
            }
        }
    }

    /**
     * Busca imports prohibidos en las fuentes de un módulo de dominio.
     * @param sources ruta relativa a la raíz del repositorio → contenido.
     */
    fun checkImports(module: String, sources: Map<String, String>): List<ModuleRuleViolation> {
        if (!isDomainModule(module)) return emptyList()
        return sources.toSortedMap().flatMap { (file, content) ->
            content.lineSequence().mapIndexedNotNull { index, line ->
                val imported = IMPORT_LINE.find(line)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                if (FORBIDDEN_DOMAIN_IMPORT_PREFIXES.none(imported::startsWith)) return@mapIndexedNotNull null
                ModuleRuleViolation(module, "import prohibido '$imported'", "$file:${index + 1}")
            }.toList()
        }
    }
}

package es.topophone.buildlogic.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reglas de arquitectura entre módulos (S001/RF-27, constitución nº 6, ADR de `docs/arquitectura.md`). */
class ModuleRulesTest {

    private fun module(path: String, android: Boolean = false, plugins: Set<String> = emptySet(), deps: Set<String> = emptySet()) =
        ModuleInfo(path = path, isAndroid = android, appliedPlugins = plugins, projectDependencies = deps)

    private val validGraph = listOf(
        module(":app", android = true, deps = setOf(":core:model", ":core:designsystem", ":data")),
        module(":data", android = true, deps = setOf(":core:model")),
        module(":core:model"),
        module(":core:designsystem", android = true, deps = setOf(":core:model")),
        module(":core:geodesy", deps = setOf(":core:model")),
        module(":core:gnss", deps = setOf(":core:model")),
        module(":core:ntrip"),
        module(":core:survey", deps = setOf(":core:model", ":core:geodesy", ":core:gnss")),
    )

    @Test
    fun `S001 RF-27 el grafo de la arquitectura no tiene infracciones`() {
        assertEquals(emptyList<ModuleRuleViolation>(), ModuleRules.checkModules(validGraph))
    }

    @Test
    fun `S001 RF-27 un modulo de dominio con el plugin de Android es una infraccion`() {
        val violations = ModuleRules.checkModules(listOf(module(":core:geodesy", android = true, deps = setOf(":core:model"))))

        assertEquals(1, violations.size)
        assertEquals(":core:geodesy", violations.single().module)
        assertTrue("Android" in violations.single().message)
    }

    @Test
    fun `S001 RF-27 un modulo de dominio con Hilt o KSP es una infraccion`() {
        val violations = ModuleRules.checkModules(
            listOf(module(":core:gnss", plugins = setOf("com.google.dagger.hilt.android", "com.google.devtools.ksp"), deps = setOf(":core:model"))),
        )

        assertEquals(2, violations.size)
    }

    @Test
    fun `S001 RF-27 core gnss no puede depender de core geodesy`() {
        val violations = ModuleRules.checkModules(listOf(module(":core:gnss", deps = setOf(":core:model", ":core:geodesy"))))

        assertEquals(listOf(":core:gnss"), violations.map { it.module })
        assertTrue(":core:geodesy" in violations.single().message)
    }

    @Test
    fun `S001 RF-27 core ntrip no puede depender de ningun otro modulo`() {
        val violations = ModuleRules.checkModules(listOf(module(":core:ntrip", deps = setOf(":core:model"))))

        assertEquals(1, violations.size)
    }

    @Test
    fun `S001 RF-27 ningun modulo core puede depender de app, data o receiver`() {
        val violations = ModuleRules.checkModules(
            listOf(
                module(":core:designsystem", android = true, deps = setOf(":data")),
                module(":core:survey", deps = setOf(":receiver")),
            ),
        )

        assertEquals(setOf(":core:designsystem", ":core:survey"), violations.map { it.module }.toSet())
    }

    @Test
    fun `S001 RF-27 nadie depende de app y solo app depende de receiver-experimental`() {
        val violations = ModuleRules.checkModules(
            listOf(
                module(":data", android = true, deps = setOf(":app")),
                module(":receiver", android = true, deps = setOf(":receiver-experimental")),
                module(":app", android = true, deps = setOf(":receiver-experimental")),
            ),
        )

        assertEquals(listOf(":data", ":receiver"), violations.map { it.module }.sorted())
    }

    @Test
    fun `S001 RF-27 un modulo core sin regla declarada es una infraccion`() {
        val violations = ModuleRules.checkModules(listOf(module(":core:inventado")))

        assertEquals(1, violations.size)
        assertTrue("arquitectura" in violations.single().message)
    }

    @Test
    fun `S001 RF-27 imports de Android, AndroidX, Dagger o javax inject en dominio se informan con ruta y linea`() {
        val source = """
            package es.topophone.core.model

            import kotlin.math.PI
            import android.location.Location
            import androidx.annotation.Keep
            import dagger.Module
            import javax.inject.Inject

            class Foo
        """.trimIndent()

        val violations = ModuleRules.checkImports(":core:model", mapOf("core/model/src/main/kotlin/Foo.kt" to source))

        assertEquals(
            listOf(
                "core/model/src/main/kotlin/Foo.kt:4",
                "core/model/src/main/kotlin/Foo.kt:5",
                "core/model/src/main/kotlin/Foo.kt:6",
                "core/model/src/main/kotlin/Foo.kt:7",
            ),
            violations.map { it.location },
        )
    }

    @Test
    fun `S001 RF-27 un fichero de dominio sin imports prohibidos no da infracciones`() {
        val source = "package es.topophone.core.model\n\nimport kotlin.math.sqrt\n// android.location no es un import\nval x = sqrt(2.0)\n"

        assertEquals(emptyList<ModuleRuleViolation>(), ModuleRules.checkImports(":core:model", mapOf("Foo.kt" to source)))
    }

    @Test
    fun `S001 RF-27 core designsystem es Android y puede importar androidx`() {
        val source = "package es.topophone.core.designsystem\n\nimport androidx.compose.runtime.Composable\n"

        assertEquals(emptyList<ModuleRuleViolation>(), ModuleRules.checkImports(":core:designsystem", mapOf("Theme.kt" to source)))
    }

    @Test
    fun `S001 RF-27 el informe nombra cada infraccion con su modulo y ubicacion`() {
        val violation = ModuleRuleViolation(module = ":core:model", message = "import prohibido 'android.location.Location'", location = "core/model/src/main/kotlin/Foo.kt:4")

        assertEquals("[:core:model] core/model/src/main/kotlin/Foo.kt:4: import prohibido 'android.location.Location'", violation.describe())
    }
}

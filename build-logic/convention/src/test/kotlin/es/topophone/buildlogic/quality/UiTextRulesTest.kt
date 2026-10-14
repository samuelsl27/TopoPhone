package es.topophone.buildlogic.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** Ningún texto visible escrito directamente en el código de la interfaz (S001/RF-19). */
class UiTextRulesTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            """Text("Levantamiento")""",
            """Text(text = "Ajustes")""",
            """Icon(painter, contentDescription = "Modo sol")""",
            """NavigationBarItem(label = { Text("Receptor") })""",
            """TopAppBar(title = "Acerca de")""",
            """OutlinedTextField(placeholder = "Nombre", value = v, onValueChange = {})""",
            """Text("${'$'}distancia m")""",
        ],
    )
    fun `S001 RF-19 se detecta un texto visible literal`(line: String) {
        val findings = UiTextRules.findHardcoded(mapOf("app/src/main/kotlin/Ui.kt" to line))

        assertEquals(listOf("app/src/main/kotlin/Ui.kt:1"), findings.map { it.location })
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            """Text(stringResource(R.string.survey_title))""",
            """Text(text = stringResource(id = R.string.settings_title))""",
            """Icon(painter, contentDescription = stringResource(R.string.sun_toggle))""",
            """Text("")""",
            """Text("${'$'}value")""",
            """Text("${'$'}{state.name}")""",
            """Log.d("TopoPhone", "mensaje de depuración")""",
            """val route = "settings/about"""",
            """Text("TopoPhone") // topophone:literal-ok nombre propio""",
            """// Text("comentado")""",
        ],
    )
    fun `S001 RF-19 no se marcan recursos, plantillas, logs, comentarios ni literales permitidos`(line: String) {
        assertEquals(emptyList<ModuleRuleViolation>(), UiTextRules.findHardcoded(mapOf("Ui.kt" to line)))
    }

    @Test
    fun `S001 RF-19 cada hallazgo indica fichero y linea`() {
        val source = """
            @Composable
            fun Pantalla() {
                Column {
                    Text(stringResource(R.string.ok))
                    Text("Sin traducir")
                }
            }
        """.trimIndent()

        val findings = UiTextRules.findHardcoded(mapOf("app/src/main/kotlin/Pantalla.kt" to source))

        assertEquals(listOf("app/src/main/kotlin/Pantalla.kt:5"), findings.map { it.location })
        assertEquals("texto visible literal \"Sin traducir\": usa un recurso de strings.xml", findings.single().message)
    }
}

package es.topophone.app.experimental

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.Test

/** S044/RF-2: el código común no importa nada del módulo experimental ni de `src/experimental`; solo ve la interfaz de ranuras. */
class MainSourcesIsolationTest {

    private val main = File("src/main/kotlin")

    @Test
    fun `S044 RF-2 ningun fichero de main importa el modulo ni el flavor experimental`() {
        main.isDirectory shouldBe true
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name != "ExperimentalFeature.kt" }
            .filter { file ->
                file.readLines().any { line ->
                    line.trimStart().startsWith("import es.topophone.receiver.experimental") ||
                        (
                            line.trimStart().startsWith("import es.topophone.app.experimental.") &&
                                !line.endsWith("ExperimentalFeature") &&
                                !line.endsWith("LocalExperimentalFeature") &&
                                !line.endsWith("LocalExperimentalNavigation")
                            )
                }
            }
            .map { it.path }
            .toList()
        offenders.shouldBeEmpty()
    }

    /** Cada ranura se llama exactamente una vez en su pantalla: dos llamadas duplicarían lo que pinta (se vio en el emulador). */
    @Test
    fun `S044 RF-6 cada ranura del codigo comun se llama una sola vez`() {
        val expected = mapOf(
            "ui/receiver/ReceiverHeader.kt" to "ReceiverHeaderSlot()",
            "ui/receiver/ReceiverList.kt" to "ReceiverListSlot()",
            "ui/receiver/ReceiverMenu.kt" to "ReceiverMenuEntry()",
            "ui/settings/SettingsScreen.kt" to "SettingsRow()",
        )

        expected.forEach { (file, call) ->
            val text = File(main, "es/topophone/app/$file").readText()
            val count = Regex(Regex.escape("LocalExperimentalFeature.current.$call")).findAll(text).count()
            "$file llama a $call $count veces" shouldBe "$file llama a $call 1 veces"
        }
        val app = File(main, "es/topophone/app/ui/TopoPhoneApp.kt").readText()
        Regex(Regex.escape("experimental.routes(")).findAll(app).count() shouldBe 1
    }
}

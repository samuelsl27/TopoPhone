package es.topophone.app.ui.options

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

/** Cada opción tiene icono, texto y etiqueta de TalkBack en español y en inglés (S059/RF-5, RF-8, RNF-T7). */
@RunWith(AndroidJUnit4::class)
class OptionPresentationTest {

    private fun context(language: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { setLocale(Locale(language)) }
        return base.createConfigurationContext(configuration)
    }

    @Test
    fun `S059 RF-8 cada opcion tiene icono, nombre y etiqueta de TalkBack en espanol y en ingles`() {
        listOf("es", "en").forEach { language ->
            val context = context(language)
            ProjectOption.entries.forEach { option ->
                val presentation = option.presentation()
                (presentation.icon != 0) shouldBe true
                context.getString(presentation.label).shouldNotBeBlank()
                context.getString(presentation.accessibilityLabel).shouldNotBeBlank()
            }
        }
    }

    @Test
    fun `S059 RF-5 solo las filas de Herramientas llevan una descripcion de una linea`() {
        ProjectOption.entries.forEach { option ->
            val hasDescription = option.presentation().description != null
            hasDescription shouldBe (option.group == OptionGroup.TOOLS)
        }
        val context = context("es")
        ProjectOptions.of(OptionGroup.TOOLS).forEach { context.getString(it.presentation().description!!).shouldNotBeBlank() }
    }

    @Test
    fun `S059 RF-8 la etiqueta de TalkBack lleva el verbo y el formato completos`() {
        val context = context("es")
        context.getString(ProjectOption.IMPORT_CAD.presentation().accessibilityLabel) shouldBe "Importar plano DXF"
        context.getString(ProjectOption.EXPORT_KML.presentation().accessibilityLabel) shouldBe "Exportar a KML o KMZ"
    }

    @Test
    fun `S059 RF-1 cada grupo tiene nombre e icono propios`() {
        val context = context("es")
        context.getString(OptionGroup.IMPORT.labelRes()) shouldBe "Importar"
        context.getString(OptionGroup.EXPORT.labelRes()) shouldBe "Exportar"
        context.getString(OptionGroup.TOOLS.labelRes()) shouldBe "Herramientas"
        OptionGroup.entries.forEach { (it.iconRes() != 0) shouldBe true }
    }
}

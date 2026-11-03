package es.topophone.app.codes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.CodeValidator
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Las bibliotecas por defecto: dos, en español e inglés, con códigos válidos y sin repetir (S023/RF-3). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class DefaultCodeLibrariesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val libraries get() = DefaultCodeLibraries(context).libraries()

    @Test
    fun `S023 RF-3 vienen dos bibliotecas por defecto con nombre en espanol y marcadas como tales`() {
        libraries.map { it.id to it.name } shouldBe listOf(
            "default:general" to "Topografía general",
            "default:urbanizacion" to "Urbanización",
        )
        libraries.all { it.isDefault } shouldBe true
    }

    @Test
    fun `S023 RF-3 cada una tiene 15 codigos validos, sin repetir y con descripcion`() {
        libraries.forEach { library ->
            library.codes.size shouldBe 15
            library.codes.map { CodeKey.of(it.code) }.toSet().size shouldBe 15
            library.codes.forEach { code ->
                CodeValidator.validate(code.code, code.attributes, emptyList()) shouldBe null
                code.description.isNotBlank() shouldBe true
            }
            library.codes.map { it.id }.toSet().size shouldBe 15
        }
    }

    @Test
    fun `S023 RF-3 el contenido es el del anexo de la spec`() {
        val general = libraries[0].codes.associate { it.code to it.geometry }
        general.keys shouldBe
            setOf("VER", "BASE", "EST", "CTL", "TER", "ARB", "POS", "PIE", "CAB", "CAM", "VAL", "ARR", "EDI", "PAR", "BAL")
        general["PIE"] shouldBe CodeGeometry.LINE
        general["EDI"] shouldBe CodeGeometry.POLYGON
        val urban = libraries[1].codes.associate { it.code to it.geometry }
        urban.keys shouldBe setOf("ARQ", "POZ", "SUM", "FAR", "SEM", "HID", "VAD", "BOR", "ACE", "CAL", "EJE", "COL", "MUR", "ZVE", "ESC")
        urban["BOR"] shouldBe CodeGeometry.LINE
        urban["ZVE"] shouldBe CodeGeometry.POLYGON
    }

    @Test
    fun `S023 RF-3 la arqueta y el colector llevan Material (lista) y Diametro (numero)`() {
        val urban = libraries[1].codes.associateBy { it.code }

        listOf("ARQ", "COL").forEach {
            urban.getValue(it).attributes.map { a -> a.name to a.type } shouldBe
                listOf("Material" to AttributeType.LIST, "Diámetro (m)" to AttributeType.NUMBER)
        }
        urban.getValue("ARQ").attributes[0].options shouldBe listOf("Hormigón", "PVC", "Fundición", "Ladrillo", "Otro")
        urban.getValue("FAR").attributes shouldBe emptyList()
    }

    @Test
    @Config(qualifiers = "en")
    fun `S023 RF-3 en ingles cambian las descripciones y los nombres pero no los codigos`() {
        libraries.map { it.name } shouldBe listOf("General surveying", "Urban development")
        libraries[0].codes.first { it.code == "VER" }.description shouldBe "Vertex"
        libraries[1].codes.map { it.code }.contains("ARQ") shouldBe true
    }
}

package es.topophone.app.ui.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/** Guardar en el sitio elegido y compartir con otras apps (S012/RF-17, RF-18, RF-20). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ExportShareTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `S012 RF-18 compartir ofrece el CSV a otras apps con permiso de lectura`() {
        val file = ExportFiles.prepare(context.cacheDir, "Calle Mayor 12_PENZD_20261018-2009.csv", nowMs = 10 * day)
        file.writeText("Name\r\n")

        // El FileProvider guarda sus carpetas para todo el proceso y Robolectric cambia de carpeta en cada test: se le da el Uri.
        val given = Uri.parse("content://${context.packageName}.fileprovider/exports/${Uri.encode(file.name)}")
        val chooser = shareExportIntent(context, file, given)

        chooser.action shouldBe Intent.ACTION_CHOOSER
        val send = IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java).shouldNotBeNull()
        send.action shouldBe Intent.ACTION_SEND
        send.type shouldBe "text/csv"
        (send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) shouldBe Intent.FLAG_GRANT_READ_URI_PERMISSION
        val uri = IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java).shouldNotBeNull()
        uri.authority shouldBe "${context.packageName}.fileprovider"
        uri.toString() shouldEndWith "Calle%20Mayor%2012_PENZD_20261018-2009.csv"
    }

    @Test
    fun `S012 RF-18 los ficheros compartidos se guardan en la cache y los de mas de un dia se borran`() {
        val folder = File(context.cacheDir, ExportFiles.DIR).apply { mkdirs() }
        val old = File(folder, "viejo.csv").apply {
            writeText("x")
            setLastModified(1 * day)
        }
        val recent = File(folder, "reciente.csv").apply {
            writeText("x")
            setLastModified(9 * day + 1)
        }

        val file = ExportFiles.prepare(context.cacheDir, "nuevo.csv", nowMs = 10 * day)

        file shouldBe File(folder, "nuevo.csv")
        old.exists() shouldBe false
        recent.exists() shouldBe true
    }

    @Test
    fun `S012 RF-17 guardar escribe en el sitio elegido`() {
        val target = File(context.filesDir, "elegido.csv")

        val ok =
            runBlocking {
                writeToUri(context.contentResolver, Uri.fromFile(target)) { out ->
                    out.write("Name\r\n".toByteArray())
                    true
                }
            }

        ok shouldBe true
        target.readText() shouldBe "Name\r\n"
    }

    @Test
    fun `S012 RF-20 si el sitio no se puede abrir no se ha exportado`() {
        val impossible = File(context.filesDir, "no/existe/aqui.csv")

        runBlocking { writeToUri(context.contentResolver, Uri.fromFile(impossible)) { true } } shouldBe false
    }

    @Test
    fun `S012 RF-17 y RF-20 la pantalla dice si el fichero se ha guardado o no`() {
        val result = mutableStateOf<ExportResult?>(ExportResult.SAVED)
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                ExportScreen(ThemeState.DEFAULT, {
                }, ExportUiState(loaded = true, projectName = "P", pointCount = 1, result = result.value), ExportActions(), onBack = {})
            }
        }

        compose.onNodeWithText("Fichero guardado.").performScrollTo().assertIsDisplayed()
        result.value = ExportResult.FAILED
        compose.onNodeWithText("No se ha podido exportar el fichero.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `S012 RF-18 el FileProvider comparte la carpeta de exportaciones de la cache`() {
        val parser = context.resources.getXml(R.xml.file_paths)
        val paths = mutableListOf<Pair<String, String?>>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG &&
                parser.name != "paths"
            ) {
                paths += parser.name to parser.getAttributeValue(null, "path")
            }
        }

        paths shouldContain ("cache-path" to "${ExportFiles.DIR}/")
    }
}

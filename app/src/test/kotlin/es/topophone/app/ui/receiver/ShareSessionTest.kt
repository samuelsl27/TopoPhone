package es.topophone.app.ui.receiver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.di.ReceiverModule
import es.topophone.app.ui.export.ExportFiles
import es.topophone.app.ui.sessions.SHARE_SESSION_TAG
import es.topophone.app.ui.sessions.SessionRow
import es.topophone.app.ui.sessions.SessionsActions
import es.topophone.app.ui.sessions.SessionsScreen
import es.topophone.app.ui.sessions.SessionsUiState
import es.topophone.app.ui.text
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.lang.reflect.Modifier
import java.time.Instant
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser

/** Compartir el fichero de la última sesión (S002/RF-43). */
@RunWith(AndroidJUnit4::class)
class ShareSessionTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * FileProvider guarda en una caché estática las carpetas raíz de cada autoridad. Robolectric
     * comparte la clase entre tests y cada test tiene su propia carpeta de datos: se vacía la caché.
     */
    @Before
    fun clearFileProviderCache() {
        FileProvider::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && Map::class.java.isAssignableFrom(it.type) }
            .forEach { field ->
                field.isAccessible = true
                val cache = field.get(null) as MutableMap<*, *>
                synchronized(cache) { cache.clear() }
            }
    }

    private fun sessionFile(content: String): File {
        val directory = File(context.filesDir, ReceiverModule.SESSIONS_DIR).apply { mkdirs() }
        return File(directory, "20261015-101530.nmea").apply { writeText(content) }
    }

    private val content = "\$GNGGA,101530.20,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001*5C\r\n"

    private fun sendIntentOf(chooser: Intent): Intent {
        chooser.action shouldBe Intent.ACTION_CHOOSER
        return IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java).shouldNotBeNull()
    }

    @Test
    fun `S002 RF-43 la sesion se ofrece como texto a las apps del movil con permiso de lectura`() {
        val file = sessionFile(content)
        val uri = Uri.parse("content://${fileProviderAuthority(context)}/sessions/${file.name}")

        val send = sendIntentOf(shareSessionIntent(context, file, uri))

        send.action shouldBe Intent.ACTION_SEND
        send.type shouldBe "text/plain"
        (send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) shouldBe Intent.FLAG_GRANT_READ_URI_PERMISSION
        IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java) shouldBe uri
        send.clipData.shouldNotBeNull().getItemAt(0).uri shouldBe uri
    }

    @Test
    fun `S002 RF-43 y S012 RF-18 el FileProvider no es publico y solo expone las sesiones y los CSV exportados`() {
        val info = context.packageManager
            .resolveContentProvider(fileProviderAuthority(context), PackageManager.GET_META_DATA)
            .shouldNotBeNull()

        info.exported shouldBe false
        info.grantUriPermissions shouldBe true
        info.metaData.getInt("android.support.FILE_PROVIDER_PATHS") shouldBe R.xml.file_paths
        val roots = mutableListOf<Triple<String, String?, String?>>()
        context.resources.getXml(R.xml.file_paths).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name != "paths") {
                    roots += Triple(parser.name, parser.getAttributeValue(null, "name"), parser.getAttributeValue(null, "path"))
                }
            }
        }
        roots shouldBe listOf(
            Triple("files-path", "sessions", "${ReceiverModule.SESSIONS_DIR}/"),
            Triple("cache-path", "exports", "${ExportFiles.DIR}/"),
        )
    }

    @Test
    fun `S002 RF-43 la otra app puede leer el fichero de la sesion por el FileProvider`() {
        // FileProvider compara rutas con '/', así que con Robolectric solo funciona fuera de Windows (CI en Linux).
        assumeFalse(File.separatorChar == '\\')
        val file = sessionFile(content)

        val uri = IntentCompat.getParcelableExtra(sendIntentOf(shareSessionIntent(context, file)), Intent.EXTRA_STREAM, Uri::class.java)
            .shouldNotBeNull()

        uri.authority shouldBe fileProviderAuthority(context)
        context.contentResolver.openInputStream(uri).shouldNotBeNull().use { it.reader().readText() } shouldBe content
    }

    private fun session(id: String, startedAt: String) =
        SessionRow(id, Instant.parse(startedAt), 60, 1_000, "reach-RS3", imported = false, inProgress = false)

    @Test
    fun `S002 RF-43 la pantalla Sesiones grabadas comparte la ultima sesion`() {
        var shared: String? = null
        val rows = listOf(session("20261015-101530", "2026-10-15T10:15:30Z"), session("20261014-083000", "2026-10-14T08:30:00Z"))
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SessionsScreen(ThemeState.DEFAULT, {
                }, SessionsUiState(rows), SessionsActions(onShare = { shared = it }), onNavigateBack = {})
            }
        }

        compose.onNodeWithTag(SHARE_SESSION_TAG).performScrollTo().assertIsEnabled().performClick()

        shared shouldBe "20261015-101530"
    }

    @Test
    fun `S002 RF-43 sin sesiones guardadas no se puede compartir y se indica`() {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                SessionsScreen(ThemeState.DEFAULT, {}, SessionsUiState(), SessionsActions(), onNavigateBack = {})
            }
        }

        compose.onNodeWithTag(SHARE_SESSION_TAG).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.receiver_no_session)).performScrollTo()
    }

    @Test
    fun `S003 RF-30 una sesion con varias partes y correcciones se comparte entera`() {
        val directory = File(context.filesDir, ReceiverModule.SESSIONS_DIR + "/20261015-101530").apply { mkdirs() }
        val files = listOf("receiver-0001.nmea", "receiver-0002.nmea", "corrections-0001.rtcm3").map {
            File(directory, it).apply { writeText(it) }
        }
        val uris = files.map { Uri.parse("content://${fileProviderAuthority(context)}/sessions/20261015-101530/${it.name}") }

        val send = sendIntentOf(shareSessionFilesIntent(context, files, uris))

        send.action shouldBe Intent.ACTION_SEND_MULTIPLE
        IntentCompat.getParcelableArrayListExtra(send, Intent.EXTRA_STREAM, Uri::class.java) shouldBe ArrayList(uris)
        val clip = send.clipData.shouldNotBeNull()
        (0 until clip.itemCount).map { clip.getItemAt(it).uri } shouldBe uris
        (send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) shouldBe Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
}

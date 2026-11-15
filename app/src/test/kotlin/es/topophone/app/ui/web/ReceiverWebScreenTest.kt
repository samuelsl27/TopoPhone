package es.topophone.app.ui.web

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.receiver.nmea
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import es.topophone.receiver.transport.StreamTransport
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * La vista web: los ajustes del WebView son los del plan D9, lo que no es la IP del receptor se bloquea (también una redirección), la
 * pantalla muestra cada fase y la sesión de posición sigue abierta con ella visible (S043/RF-12 a RF-17). Interfaz en es-ES.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ReceiverWebScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = mutableListOf<String>()
    private val actions = ReceiverWebActions(
        onSubmitAddress = { calls += "submit:$it" },
        onPageFinished = { calls += "finished" },
        onLoadError = { calls += "error" },
        onRetry = { calls += "retry" },
        onOpenBrowser = { calls += "browser:$it" },
    )
    private val events = object : WebPageEvents {
        override fun onPageFinished() {
            calls += "finished"
        }

        override fun onLoadError() {
            calls += "error"
        }
    }

    private fun show(state: ReceiverWebUiState) {
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) { ReceiverWebScreen(ThemeState.DEFAULT, {}, {}, state, actions) }
        }
    }

    private class Request(private val address: String, private val mainFrame: Boolean = true) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(address)

        override fun isForMainFrame() = mainFrame

        override fun isRedirect() = false

        override fun hasGesture() = false

        override fun getMethod() = "GET"

        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    // ------------------------------------------------------------------------------------ ajustes del WebView (D9)
    @Test
    fun `S043 RF-15 el WebView lleva JavaScript y almacenamiento y ni ficheros, ni contenido, ni ventanas, ni contenido mixto`() {
        val view = WebView(context)

        RestrictedWebView.configure(view, WebAddressPolicy("192.168.42.1"), events)

        with(view.settings) {
            javaScriptEnabled shouldBe true
            domStorageEnabled shouldBe true
            allowFileAccess shouldBe false
            allowContentAccess shouldBe false
            mixedContentMode shouldBe WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically shouldBe false
            supportMultipleWindows() shouldBe false
        }
        CookieManager.getInstance().acceptThirdPartyCookies(view) shouldBe false
    }

    // ------------------------------------------------------------------------------------ direcciones (RF-14)
    @Test
    fun `S043 RF-14 una peticion a una direccion publica se bloquea y la del receptor sigue`() {
        val view = WebView(context)
        val client = RestrictedWebView.client(WebAddressPolicy("192.168.42.1"), events)

        client.shouldInterceptRequest(view, Request("http://8.8.8.8/x")).shouldNotBeNull().statusCode shouldBe 403
        client.shouldInterceptRequest(view, Request("http://192.168.42.1/panel")).shouldBeNull()
        client.shouldOverrideUrlLoading(view, Request("http://8.8.8.8/")) shouldBe true
        client.shouldOverrideUrlLoading(view, Request("http://192.168.42.1:8080/panel")) shouldBe false
    }

    @Test
    fun `S043 RF-14 una peticion a otro puerto de otra IP se bloquea`() {
        val view = WebView(context)
        val client = RestrictedWebView.client(WebAddressPolicy("192.168.42.1"), events)

        client.shouldInterceptRequest(view, Request("http://192.168.42.9:8080/")).shouldNotBeNull().statusCode shouldBe 403
        client.shouldOverrideUrlLoading(view, Request("http://192.168.42.9:8080/")) shouldBe true
    }

    @Test
    fun `S043 RF-14 una redireccion a otra direccion no se sigue`() {
        val view = WebView(context)
        val client = RestrictedWebView.client(WebAddressPolicy("192.168.42.1"), events)

        // El servidor de la demo (tools/simulador/servidor_web.py) redirige a una IP publica.
        client.shouldOverrideUrlLoading(view, Request("http://8.8.8.8/")) shouldBe true
        client.shouldOverrideUrlLoading(view, Request("http://192.168.9.9/")) shouldBe true
    }

    @Test
    fun `S043 RF-16 el fin de la carga y los errores de la pagina principal se cuentan y los de un recurso no`() {
        val view = WebView(context)
        val client = RestrictedWebView.client(WebAddressPolicy("192.168.42.1"), events)
        val notFound = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        val ok = WebResourceResponse("text/plain", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))

        client.onPageFinished(view, "http://192.168.42.1/")
        RestrictedWebView.reportError(mainFrameFailed = true, events = events)
        RestrictedWebView.reportError(mainFrameFailed = false, events = events)
        client.onReceivedHttpError(view, Request("http://192.168.42.1/"), notFound)
        client.onReceivedHttpError(view, Request("http://192.168.42.1/x.js", mainFrame = false), notFound)
        client.onReceivedHttpError(view, Request("http://192.168.42.1/"), ok)

        calls shouldBe listOf("finished", "error", "error")
    }

    // ------------------------------------------------------------------------------------ fases de la pantalla
    @Test
    fun `S043 RF-22 con una fuente que no es un receptor se dice que no aplica`() {
        show(ReceiverWebUiState(phase = WebPhase.NOT_APPLICABLE))

        compose.onNodeWithTag(WEB_NOT_APPLICABLE_TAG).assertIsDisplayed()
    }

    @Test
    fun `S043 RF-13 sin direccion se pide la IP y se envia lo escrito`() {
        show(ReceiverWebUiState(phase = WebPhase.ASK_ADDRESS, receiverKey = "AA:BB"))

        compose.onNodeWithTag(WEB_ADDRESS_TAG).performTextInput("192.168.42.1")
        compose.onNodeWithTag(WEB_ADDRESS_OPEN_TAG).performScrollTo().performClick()

        calls shouldBe listOf("submit:192.168.42.1")
    }

    @Test
    fun `S043 RF-14 una direccion rechazada se explica`() {
        show(ReceiverWebUiState(phase = WebPhase.ASK_ADDRESS, receiverKey = "AA:BB", addressRejected = true))

        compose.onNodeWithTag(WEB_ADDRESS_REJECTED_TAG).performScrollTo().assertTextContains("IP privadas", substring = true)
    }

    @Test
    fun `S043 RF-15 mientras se carga se avisa de que el movil usa el Wi-Fi del receptor`() {
        show(ReceiverWebUiState(phase = WebPhase.LOADING, url = "http://192.168.42.1:80/", networkReady = true))

        compose.onNodeWithTag(WEB_WIFI_NOTE_TAG).assertIsDisplayed()
        compose.onNodeWithTag(WEB_LOADING_TAG).assertIsDisplayed()
        compose.onNodeWithTag(WEB_VIEW_TAG).assertExists()
    }

    @Test
    fun `S043 RF-16 el fallo muestra las causas y las dos salidas`() {
        val url = "http://192.168.42.1:80/"
        show(ReceiverWebUiState(phase = WebPhase.FAILED, url = url, causes = WebFailureCause.entries))

        WebFailureCause.entries.forEach { compose.onNodeWithTag(webCauseTag(it)).performScrollTo().assertIsDisplayed() }
        compose.onNodeWithTag(WEB_RETRY_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(WEB_BROWSER_TAG).performScrollTo().performClick()

        calls shouldBe listOf("retry", "browser:$url")
    }

    // ------------------------------------------------------------------------------------ la sesion (RF-17)
    @Test
    fun `S043 RF-17 con la vista visible la conexion de posicion sigue abierta`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val toApp = PipedOutputStream()
        val session = ReceiverSession(scope, NtripConnector { _, _, _, _ -> NtripFailure.UNREACHABLE }, noDataTimeoutMs = 600_000)
        try {
            session.connect(StreamTransport("reach-RS3", PipedInputStream(toApp, 64 * 1024), ByteArrayOutputStream()))
            toApp.write(nmea("GNGGA,101530.00,4025.0080000,N,00342.2228000,W,4,24,0.6,650.123,M,51.234,M,1.0,0001").toByteArray())
            toApp.flush()
            runBlocking { withTimeout(10_000) { session.state.first { it.connection is ConnectionState.Receiving } } }

            show(ReceiverWebUiState(phase = WebPhase.LOADING, url = "http://192.168.42.1:80/", networkReady = true))
            compose.onNodeWithTag(WEB_VIEW_TAG).assertExists()
            compose.waitForIdle()

            (session.state.value.connection is ConnectionState.Receiving) shouldBe true
        } finally {
            runCatching { toApp.close() }
            session.disconnect()
            scope.cancel()
        }
    }
}

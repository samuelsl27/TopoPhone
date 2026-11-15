package es.topophone.app.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** Lo que la vista web cuenta de la carga de la página principal (S043/RF-16). */
interface WebPageEvents {
    fun onPageFinished()

    fun onLoadError()
}

/**
 * Deja un `WebView` como lo pide el plan D9 (S043/RF-14, RF-15): JavaScript y almacenamiento DOM sí (el panel es una aplicación web);
 * ficheros, contenido de otras apps, geolocalización, ventanas nuevas, contenido mixto y cookies de terceros, no; y ningún puente con
 * la app (nunca se llama a `addJavascriptInterface`). Solo se cargan las direcciones que [policy] permite, también en redirecciones y
 * en los recursos de la página.
 */
object RestrictedWebView {

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    @SuppressLint("SetJavaScriptEnabled") // El panel del receptor es una aplicación web: sin JavaScript no funciona (plan D9).
    fun configure(webView: WebView, policy: WebAddressPolicy, events: WebPageEvents) {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = client(policy, events)
    }

    /** El cliente que filtra por [policy] y avisa a [events] de cómo va la carga. */
    fun client(policy: WebAddressPolicy, events: WebPageEvents): WebViewClient = object : WebViewClient() {
        // Un enlace o una redirección de la página principal: true es no seguirlo.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !policy.allows(request.url.toString())

        // Los recursos de la página (imágenes, scripts, peticiones): lo que no se permite recibe un 403 vacío y no sale a la red.
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            if (policy.allows(request.url.toString())) null else blocked()

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = Unit

        override fun onPageFinished(view: WebView, url: String?) = events.onPageFinished()

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
            reportError(request.isForMainFrame, events)

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) =
            reportError(request.isForMainFrame && errorResponse.statusCode >= HTTP_ERROR_FROM, events)

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            events.onLoadError()
            return true
        }
    }

    /** Un error de la página principal es un fallo de la carga (RF-16); el de un recurso suelto (una imagen, un script) no. */
    internal fun reportError(mainFrameFailed: Boolean, events: WebPageEvents) {
        if (mainFrameFailed) events.onLoadError()
    }

    private const val HTTP_ERROR_FROM = 400
}

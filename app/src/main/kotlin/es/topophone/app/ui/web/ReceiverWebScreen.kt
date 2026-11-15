package es.topophone.app.ui.web

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.preferences.ThemeState

const val WEB_SCREEN_TAG = "web_screen"
const val WEB_NOT_APPLICABLE_TAG = "web_not_applicable"
const val WEB_ADDRESS_TAG = "web_address"
const val WEB_ADDRESS_OPEN_TAG = "web_address_open"
const val WEB_ADDRESS_REJECTED_TAG = "web_address_rejected"
const val WEB_LOADING_TAG = "web_loading"
const val WEB_VIEW_TAG = "web_view"
const val WEB_WIFI_NOTE_TAG = "web_wifi_note"
const val WEB_FAILED_TAG = "web_failed"
const val WEB_RETRY_TAG = "web_retry"
const val WEB_BROWSER_TAG = "web_browser"

fun webCauseTag(cause: WebFailureCause) = "web_cause_${cause.name.lowercase()}"

/** Lo que la vista web pide al ViewModel (S043/RF-13, RF-16). */
class ReceiverWebActions(
    val onSubmitAddress: (String) -> Unit = {},
    val onPageFinished: () -> Unit = {},
    val onLoadError: () -> Unit = {},
    val onRetry: () -> Unit = {},
    /** Abre [url] en el navegador del móvil; por defecto, con `ACTION_VIEW`. */
    val onOpenBrowser: ((String) -> Unit)? = null,
)

@Composable
private fun causeText(cause: WebFailureCause): String = stringResource(
    when (cause) {
        WebFailureCause.OUT_OF_RECEIVER_NETWORK -> R.string.web_cause_out_of_network
        WebFailureCause.WRONG_ADDRESS_OR_PORT -> R.string.web_cause_wrong_address
        WebFailureCause.NO_WEB_INTERFACE -> R.string.web_cause_no_web
        WebFailureCause.OTHER_CLIENT_CONNECTED -> R.string.web_cause_other_client
    },
)

/**
 * La interfaz web del receptor dentro de la app (S043/RF-12 a RF-17): según la fase, la explicación de que no aplica, el formulario
 * de la IP, la página en un `WebView` restringido o el fallo con sus causas, reintentar y abrir en el navegador. No toca la
 * conexión de posición: la sesión vive en el servicio, no en esta pantalla (RF-17).
 */
@Composable
fun ReceiverWebScreen(
    themeState: ThemeState,
    onToggleSun: () -> Unit,
    onBack: () -> Unit,
    state: ReceiverWebUiState,
    actions: ReceiverWebActions,
) {
    SectionScaffold(
        title = stringResource(R.string.web_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = onBack,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().testTag(WEB_SCREEN_TAG)) {
            when (state.phase) {
                WebPhase.NOT_APPLICABLE -> Message(R.string.web_not_applicable, WEB_NOT_APPLICABLE_TAG)
                WebPhase.ASK_ADDRESS -> AskAddress(state, actions)
                WebPhase.LOADING, WebPhase.LOADED -> {
                    Text(
                        stringResource(R.string.web_wifi_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(WEB_WIFI_NOTE_TAG),
                    )
                    if (state.phase == WebPhase.LOADING) {
                        Text(stringResource(R.string.web_loading), modifier = Modifier.padding(horizontal = 16.dp).testTag(WEB_LOADING_TAG))
                    }
                    PageView(state, actions)
                }
                WebPhase.FAILED -> Failed(state, actions)
            }
        }
    }
}

@Composable
private fun Message(text: Int, tag: String) {
    Text(stringResource(text), modifier = Modifier.padding(16.dp).testTag(tag), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun AskAddress(state: ReceiverWebUiState, actions: ReceiverWebActions) {
    var text by rememberSaveable(state.receiverKey, state.savedAddress) { mutableStateOf(state.savedAddress) }
    Column(
        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.web_ask_hint), style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.web_address_label)) },
            singleLine = true,
            isError = state.addressRejected,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { actions.onSubmitAddress(text) }),
            modifier = Modifier.fillMaxWidth().testTag(WEB_ADDRESS_TAG),
        )
        if (state.addressRejected) {
            Text(
                stringResource(R.string.web_address_rejected),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(WEB_ADDRESS_REJECTED_TAG),
            )
        }
        Button(
            onClick = { actions.onSubmitAddress(text) },
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(WEB_ADDRESS_OPEN_TAG),
        ) { Text(stringResource(R.string.web_address_open)) }
    }
}

@Composable
private fun Failed(state: ReceiverWebUiState, actions: ReceiverWebActions) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()).testTag(WEB_FAILED_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.web_failed_title), style = MaterialTheme.typography.titleMedium)
        state.causes.forEach { Text(causeText(it), modifier = Modifier.testTag(webCauseTag(it))) }
        Button(
            onClick = actions.onRetry,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(WEB_RETRY_TAG),
        ) { Text(stringResource(R.string.web_retry)) }
        OutlinedButton(
            onClick = {
                val url = state.url ?: return@OutlinedButton
                val open = actions.onOpenBrowser
                if (open != null) {
                    open(url)
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            enabled = state.url != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).testTag(WEB_BROWSER_TAG),
        ) { Text(stringResource(R.string.web_open_browser)) }
    }
}

/** El `WebView` restringido; carga la dirección cada vez que cambia [ReceiverWebUiState.loadId] y el móvil ya usa el Wi-Fi del receptor. */
@Composable
private fun PageView(state: ReceiverWebUiState, actions: ReceiverWebActions) {
    val url = state.url ?: return
    val finished = rememberUpdatedState(actions.onPageFinished)
    val failed = rememberUpdatedState(actions.onLoadError)
    val events = remember {
        object : WebPageEvents {
            override fun onPageFinished() = finished.value()

            override fun onLoadError() = failed.value()
        }
    }
    val policy = remember(url) { WebAddressPolicy.forUrl(url) }
    if (policy == null) {
        failed.value()
        return
    }
    var loadedId by remember(url) { mutableStateOf(-1) }
    AndroidView(
        modifier = Modifier.fillMaxSize().testTag(WEB_VIEW_TAG),
        factory = { context -> WebView(context).also { RestrictedWebView.configure(it, policy, events) } },
        update = { view ->
            if (state.networkReady && loadedId != state.loadId) {
                loadedId = state.loadId
                view.loadUrl(url)
            }
        },
        onRelease = { it.destroy() },
    )
}

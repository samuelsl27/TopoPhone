package es.topophone.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import java.util.Locale

const val BACKGROUND_HELP_BATTERY_TAG = "background_help_battery"

fun phoneMakerTag(maker: PhoneMaker) = "phone_maker_${maker.name}"

/** Fabricantes que cierran apps en segundo plano, con los nombres con los que se identifican sus móviles. */
enum class PhoneMaker(@param:StringRes val title: Int, @param:StringRes val steps: Int, val brands: Set<String>) {
    SAMSUNG(R.string.background_help_samsung_title, R.string.background_help_samsung_steps, setOf("samsung")),
    XIAOMI(R.string.background_help_xiaomi_title, R.string.background_help_xiaomi_steps, setOf("xiaomi", "redmi", "poco")),
    HUAWEI(R.string.background_help_huawei_title, R.string.background_help_huawei_steps, setOf("huawei", "honor")),
    OPPO(R.string.background_help_oppo_title, R.string.background_help_oppo_steps, setOf("oppo", "realme", "oneplus")),
}

/** Los pasos del fabricante del móvil van los primeros; los demás, en su orden. */
fun phoneMakersFor(manufacturer: String): List<PhoneMaker> {
    val own = PhoneMaker.entries.firstOrNull { manufacturer.trim().lowercase(Locale.ROOT) in it.brands }
    return listOfNotNull(own) + PhoneMaker.entries.filter { it != own }
}

/** Ajustes de TopoPhone en el sistema: de ahí se entra en su batería (no hay acceso público directo). */
fun appSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

/** Ayuda para que el fabricante del móvil no cierre la app en segundo plano (S004/RF-12, RNF-T2). */
@Composable
fun BackgroundHelpScreen(uiState: AppUiState, actions: AppActions, onNavigateBack: () -> Unit) {
    SectionScaffold(
        title = stringResource(R.string.background_help_title),
        themeState = uiState.themeState,
        onToggleSun = actions.onToggleSun,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            BackgroundHelpContent(Build.MANUFACTURER.orEmpty(), actions.onOpenAppBatterySettings)
        }
    }
}

@Composable
internal fun BackgroundHelpContent(manufacturer: String, onOpenAppBatterySettings: () -> Unit) {
    val makers = phoneMakersFor(manufacturer)
    val own = makers.first().takeIf { manufacturer.trim().lowercase(Locale.ROOT) in it.brands }
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.background_help_intro))
        Text(stringResource(R.string.background_help_general_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.background_help_general_steps))
        Button(
            onClick = onOpenAppBatterySettings,
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(BACKGROUND_HELP_BATTERY_TAG),
        ) { Text(stringResource(R.string.background_help_open_battery)) }
        makers.forEach { maker ->
            Column(modifier = Modifier.testTag(phoneMakerTag(maker)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val title = stringResource(maker.title)
                Text(
                    if (maker == own) stringResource(R.string.background_help_your_phone, title) else title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(stringResource(maker.steps))
            }
        }
        Text(stringResource(R.string.background_help_note), style = MaterialTheme.typography.bodySmall)
    }
}

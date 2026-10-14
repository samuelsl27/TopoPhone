package es.topophone.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import es.topophone.core.designsystem.component.SettingsInfo
import es.topophone.core.designsystem.component.SettingsItem

const val ABOUT_NAME_TAG = "about_name"
const val ABOUT_VERSION_TAG = "about_version"
const val ABOUT_COMMIT_TAG = "about_commit"
const val ABOUT_LICENSE_TAG = "about_license"
const val ABOUT_REPOSITORY_TAG = "about_repository"
const val ABOUT_DISCLAIMER_TAG = "about_disclaimer"

/** "Acerca de": datos de la compilación, licencia, repositorio y aviso de no afiliación (S001/RF-20 a RF-22). */
@Composable
fun AboutScreen(uiState: AppUiState, actions: AppActions, onNavigateBack: () -> Unit) {
    val info = uiState.appInfo
    SectionScaffold(
        title = stringResource(R.string.about_title),
        themeState = uiState.themeState,
        onToggleSun = actions.onToggleSun,
        onNavigateBack = onNavigateBack,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SettingsInfo(
                stringResource(R.string.about_name),
                stringResource(R.string.app_name),
                Modifier.testTag(ABOUT_NAME_TAG),
            )
            SettingsInfo(stringResource(R.string.about_version), info.versionName, Modifier.testTag(ABOUT_VERSION_TAG))
            SettingsInfo(stringResource(R.string.about_commit), info.gitCommit, Modifier.testTag(ABOUT_COMMIT_TAG))
            SettingsInfo(
                stringResource(R.string.about_license),
                stringResource(R.string.about_license_value),
                Modifier.testTag(ABOUT_LICENSE_TAG),
            )
            SettingsItem(
                title = stringResource(R.string.about_repository),
                value = info.repoUrl,
                onClick = { actions.onOpenUrl(info.repoUrl) },
                modifier = Modifier.testTag(ABOUT_REPOSITORY_TAG),
            )
            Text(
                stringResource(R.string.about_disclaimer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).testTag(ABOUT_DISCLAIMER_TAG),
            )
        }
    }
}

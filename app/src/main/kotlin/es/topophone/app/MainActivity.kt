package es.topophone.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppInfo
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.TopoPhoneApp
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState

/**
 * Única actividad. Hereda de AppCompatActivity para que el idioma por app funcione
 * también en Android 8–12 (S001/RF-18).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appInfo = AppInfo(versionName = BuildConfig.VERSION_NAME, gitCommit = BuildConfig.GIT_COMMIT, repoUrl = BuildConfig.REPO_URL)
        setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                TopoPhoneApp(
                    uiState = AppUiState(themeState = ThemeState.DEFAULT, language = AppLanguage.SYSTEM, appInfo = appInfo),
                    actions = AppActions(onToggleSun = {}, onSelectTheme = {}, onSelectLanguage = {}, onOpenUrl = {}),
                )
            }
        }
    }
}

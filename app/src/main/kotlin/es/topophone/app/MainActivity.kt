package es.topophone.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppInfo
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.TopoPhoneApp
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.EffectiveTheme

/**
 * Única actividad. Hereda de AppCompatActivity para que el idioma por app funcione
 * también en Android 8–12 (S001/RF-18).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appInfo = AppInfo(versionName = BuildConfig.VERSION_NAME, gitCommit = BuildConfig.GIT_COMMIT, repoUrl = BuildConfig.REPO_URL)

        setContent {
            val loadedTheme by viewModel.themeState.collectAsStateWithLifecycle()
            // Hasta leer el tema guardado solo se ve el fondo de la ventana (S001/RF-15).
            val themeState = loadedTheme ?: return@setContent
            // Sistema sigue al modo oscuro del dispositivo, también si cambia con la app abierta (S001/RF-10).
            val effectiveTheme = themeState.effective(isSystemInDarkTheme())

            DisposableEffect(effectiveTheme) {
                val style = if (effectiveTheme == EffectiveTheme.DARK) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }

            TopoPhoneTheme(effectiveTheme) {
                TopoPhoneApp(
                    uiState = AppUiState(themeState = themeState, language = AppLanguage.SYSTEM, appInfo = appInfo),
                    actions = AppActions(
                        onToggleSun = viewModel::toggleSun,
                        onSelectTheme = viewModel::selectTheme,
                        onSelectLanguage = {},
                        onOpenUrl = {},
                    ),
                )
            }
        }
    }
}

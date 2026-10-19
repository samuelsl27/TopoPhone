package es.topophone.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import es.topophone.app.backup.AutoBackupScheduler
import es.topophone.app.di.ApplicationScope
import es.topophone.app.receiver.CorrectionsController
import es.topophone.app.receiver.ReceiverConnections
import es.topophone.app.ui.AppActions
import es.topophone.app.ui.AppInfo
import es.topophone.app.ui.AppUiState
import es.topophone.app.ui.TopoPhoneApp
import es.topophone.app.ui.projects.TrashPurge
import es.topophone.app.ui.settings.appSettingsIntent
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.AppLanguage
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.data.backup.BackupChanges
import es.topophone.data.ntrip.NtripLegacyImport
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Única actividad. Hereda de AppCompatActivity para que el idioma por app funcione
 * también en Android 8–12 (S001/RF-18).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var connections: ReceiverConnections

    @Inject lateinit var trashPurge: TrashPurge

    /** Se crea con la actividad para vigilar la conexión del receptor y abrir el NTRIP solo (S007/RF-26). */
    @Inject lateinit var corrections: CorrectionsController

    @Inject lateinit var ntripLegacyImport: NtripLegacyImport

    @Inject lateinit var autoBackups: AutoBackupScheduler

    @Inject lateinit var backupChanges: BackupChanges

    @Inject @ApplicationScope
    lateinit var appScope: CoroutineScope

    /** Cuántas veces se ha pedido abrir Receptor desde la notificación (S004/RF-9). */
    private val openReceiverRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        lifecycleScope.launch { connections.autoConnect() } // S004/RF-25: una sola vez por arranque
        lifecycleScope.launch { trashPurge.runOnce() } // S006/RF-27: la papelera se purga al abrir la app
        lifecycleScope.launch { ntripLegacyImport.run() } // S007/RF-10: los datos de caster de S002 pasan a un perfil
        autoBackups.start() // S013/RF-18: una vez por proceso
        // Al recrearse la actividad, la navegación ya está restaurada: el extra solo cuenta la primera vez.
        if (savedInstanceState == null) handleOpenReceiver(intent)
        val appInfo = AppInfo(versionName = BuildConfig.VERSION_NAME, gitCommit = BuildConfig.GIT_COMMIT, repoUrl = BuildConfig.REPO_URL)

        setContent {
            val loadedTheme by viewModel.themeState.collectAsStateWithLifecycle()
            val sessionSpaceMb by viewModel.sessionSpaceMb.collectAsStateWithLifecycle()
            val correctionsAlarmS by viewModel.correctionsAlarmS.collectAsStateWithLifecycle()
            val openReceiverRequest by openReceiverRequests.collectAsStateWithLifecycle()
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
                    uiState = AppUiState(
                        themeState = themeState,
                        language = currentLanguage(),
                        appInfo = appInfo,
                        sessionSpaceMb = sessionSpaceMb,
                        correctionsAlarmS = correctionsAlarmS,
                    ),
                    actions = AppActions(
                        onToggleSun = viewModel::toggleSun,
                        onSelectTheme = viewModel::selectTheme,
                        onSelectLanguage = ::applyLanguage,
                        onOpenUrl = ::openUrl,
                        onSelectSessionSpace = viewModel::selectSessionSpace,
                        onSelectCorrectionsAlarm = viewModel::selectCorrectionsAlarm,
                        onOpenAppBatterySettings = { startActivity(appSettingsIntent(packageName)) },
                    ),
                    openReceiverRequest = openReceiverRequest,
                )
            }
        }
    }

    /** S013/RF-18: al pasar a segundo plano se mira si toca una copia automática; girar la pantalla no cuenta. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) autoBackups.onBackground()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenReceiver(intent)
    }

    private fun handleOpenReceiver(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_OPEN_RECEIVER, false)) {
            intent.removeExtra(EXTRA_OPEN_RECEIVER)
            openReceiverRequests.update { it + 1 }
        }
    }

    /** Abre un enlace en el navegador; si no hay ninguno instalado, no hace nada (S001/RF-20). */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // Sin navegador: el enlace sigue visible en pantalla.
        }
    }

    /** Idioma elegido para la app; vacío = idioma del sistema (S001/RF-16). */
    private fun currentLanguage(): AppLanguage = AppLanguage.fromLanguageTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

    /**
     * AppCompat guarda la elección y recrea la actividad en Android 8–12; en Android 13+ lo hace el
     * sistema. La navegación se conserva en la recreación, así que se sigue en Ajustes (S001/RF-18).
     */
    private fun applyLanguage(language: AppLanguage) {
        appScope.launch { backupChanges.languageChanged() } // S013/RF-18: el idioma también viaja en la copia
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.languageTags))
    }

    companion object {
        /** Extra con el que la notificación del receptor abre la sección Receptor (S004/RF-9). */
        const val EXTRA_OPEN_RECEIVER = "es.topophone.app.extra.OPEN_RECEIVER"
    }
}

package es.topophone.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.res.stringResource
import dagger.hilt.android.AndroidEntryPoint
import es.topophone.core.designsystem.component.EmptyState
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.preferences.EffectiveTheme

/**
 * Única actividad. Hereda de AppCompatActivity para que el idioma por app funcione
 * también en Android 8–12 (S001/RF-18).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                EmptyState(title = stringResource(R.string.app_name), description = stringResource(R.string.app_tagline))
            }
        }
    }
}

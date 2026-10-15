package es.topophone.core.designsystem.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import es.topophone.core.model.preferences.EffectiveTheme

/** Tema que se está pintando, expuesto en la semántica para los tests de interfaz. */
val EffectiveThemeKey = SemanticsPropertyKey<EffectiveTheme>("EffectiveTheme")
var SemanticsPropertyReceiver.effectiveTheme by EffectiveThemeKey

/** Tema de TopoPhone: aplica el esquema de color y el fondo del tema efectivo. */
@Composable
fun TopoPhoneTheme(effectiveTheme: EffectiveTheme, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorSchemeFor(effectiveTheme)) {
        CompositionLocalProvider(LocalStatusPalette provides statusPaletteFor(effectiveTheme)) {
            Surface(
                modifier = Modifier.fillMaxSize().semantics { this.effectiveTheme = effectiveTheme },
                color = MaterialTheme.colorScheme.background,
                content = content,
            )
        }
    }
}

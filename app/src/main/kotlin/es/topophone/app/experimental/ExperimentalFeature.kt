package es.topophone.app.experimental

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import es.topophone.core.model.preferences.ThemeState

/**
 * Lo único que el código común conoce del módulo experimental (S044/RF-1, RF-2, ADR-21): una interfaz con ranuras.
 * `standard` la enlaza a [NoExperimentalFeature], que no pinta nada; `experimental`, a la implementación real.
 * El código común nunca importa clases del módulo `receiver-experimental` ni de `src/experimental`.
 */
interface ExperimentalFeature {
    /** `true` solo en el flavor `experimental`. */
    val available: Boolean

    /** Fila de Ajustes con el interruptor «Módulo experimental» (S044/RF-3). */
    @Composable
    fun SettingsRow()

    /** Marca «Experimental» en la cabecera de la pantalla del receptor (S044/RF-6). */
    @Composable
    fun ReceiverHeaderSlot()

    /** Entrada «Módulo experimental» del menú del receptor (S044/RF-6). */
    @Composable
    fun ReceiverMenuEntry()

    /** Botón «Buscar receptores» de la lista de receptores (S044/RF-7). */
    @Composable
    fun ReceiverListSlot()

    /** Pantallas del módulo experimental dentro del grafo del receptor. */
    fun routes(builder: NavGraphBuilder, navController: NavController, themeState: ThemeState, onToggleSun: () -> Unit)
}

/** Implementación de `standard`: no pinta ni registra nada (S044/RF-1). */
class NoExperimentalFeature : ExperimentalFeature {
    override val available: Boolean = false

    @Composable
    override fun SettingsRow() = Unit

    @Composable
    override fun ReceiverHeaderSlot() = Unit

    @Composable
    override fun ReceiverMenuEntry() = Unit

    @Composable
    override fun ReceiverListSlot() = Unit

    override fun routes(builder: NavGraphBuilder, navController: NavController, themeState: ThemeState, onToggleSun: () -> Unit) = Unit
}

/** Las pantallas la leen de aquí; sin proveedor (en los tests de pantalla) es la vacía, así no cambia ninguna. */
val LocalExperimentalFeature = compositionLocalOf<ExperimentalFeature> { NoExperimentalFeature() }

/**
 * Cómo una ranura abre una pantalla del módulo: recibe la ruta (un objeto serializable de Navigation Compose) y navega a ella. Sin
 * proveedor (en los tests de pantalla) no hace nada. Lo da `AppNavHost` (S044/RF-6).
 */
val LocalExperimentalNavigation = staticCompositionLocalOf<(Any) -> Unit> { {} }

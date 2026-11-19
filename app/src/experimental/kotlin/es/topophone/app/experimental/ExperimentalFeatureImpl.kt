package es.topophone.app.experimental

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.launch

/** Implementación real de las ranuras (S044/RF-1, ADR-21); las pantallas llegan en T15 a T20. */
@Singleton
class ExperimentalFeatureImpl(private val preferences: ExperimentalPreferences, private val now: () -> Long) : ExperimentalFeature {

    @Inject
    constructor(preferences: ExperimentalPreferences) : this(preferences, System::currentTimeMillis)

    override val available: Boolean = true

    @Composable
    override fun SettingsRow() {
        val acceptedAt = preferences.acceptedAtMs.collectAsState(initial = null).value
        val scope = rememberCoroutineScope()
        ExperimentalSettingsRow(
            active = acceptedAt != null,
            onAccept = { scope.launch { preferences.accept(now()) } },
            onDeactivate = { scope.launch { preferences.clear() } },
        )
    }

    @Composable
    override fun ReceiverHeaderSlot() = Unit

    @Composable
    override fun ReceiverMenuEntry() = Unit

    @Composable
    override fun ReceiverListSlot() = Unit

    override fun routes(builder: NavGraphBuilder, navController: NavController) = Unit
}

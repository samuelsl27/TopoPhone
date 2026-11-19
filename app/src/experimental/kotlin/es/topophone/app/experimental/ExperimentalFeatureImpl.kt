package es.topophone.app.experimental

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import javax.inject.Inject
import javax.inject.Singleton

/** Implementación real de las ranuras (S044/RF-1, ADR-21); las pantallas llegan en T13 a T20. */
@Singleton
class ExperimentalFeatureImpl @Inject constructor() : ExperimentalFeature {
    override val available: Boolean = true

    @Composable
    override fun SettingsRow() = Unit

    @Composable
    override fun ReceiverHeaderSlot() = Unit

    @Composable
    override fun ReceiverMenuEntry() = Unit

    @Composable
    override fun ReceiverListSlot() = Unit

    override fun routes(builder: NavGraphBuilder, navController: NavController) = Unit
}

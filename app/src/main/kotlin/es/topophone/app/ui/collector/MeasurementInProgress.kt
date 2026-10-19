package es.topophone.app.ui.collector

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Si hay una medida de un punto en curso, también el punto medido que espera a que se confirme (S013/RF-16). Lo pone
 * el recolector; lo miran la restauración y la sustitución de un proyecto, que no se hacen mientras tanto.
 */
@Singleton
class MeasurementInProgress @Inject constructor() {
    private val mutable = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutable.asStateFlow()

    fun set(active: Boolean) {
        mutable.value = active
    }
}

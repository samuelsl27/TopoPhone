package es.topophone.app.receiver

import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.survey.BaseChange
import es.topophone.core.survey.BaseState
import es.topophone.core.survey.Baseline
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Los avisos de la base que el usuario ha cerrado: el número del cambio y la base de la línea larga (S015/RF-15, RF-17). */
data class DismissedNotices(val changeCount: Int = 0, val longBaselineSerial: Int = 0)

/** Los avisos que se ven ahora: la línea base larga, en metros, y el cambio de base. */
data class VisibleNotices(val longBaselineM: Double?, val change: BaseChange?, val baseSerial: Int)

/**
 * Los avisos cerrados, comunes a la pantalla Estado y al recolector: cerrar uno en una pantalla lo cierra en la otra
 * (plan de S015, `BaseNotices`). Viven lo que el proceso.
 */
@Singleton
class BaseNotices @Inject constructor() {
    private val mutable = MutableStateFlow(DismissedNotices())
    val dismissed: StateFlow<DismissedNotices> = mutable.asStateFlow()

    fun dismissChange(count: Int) = mutable.update { it.copy(changeCount = count) }

    fun dismissLongBaseline(serial: Int) = mutable.update { it.copy(longBaselineSerial = serial) }

    companion object {
        /**
         * "Línea base larga" con más de 20 km mientras no se cierre para esa base; vuelve con cada base nueva (RF-15).
         * "La base ha cambiado" con cada cambio que no se haya cerrado (RF-17).
         */
        fun visible(gga: GgaFix?, base: BaseState, dismissed: DismissedNotices): VisibleNotices {
            val position = base.position
            val baseline = if (position != null && gga != null) Baseline.fromGga(gga, position.ecef) else null
            return VisibleNotices(
                longBaselineM = baseline?.takeIf { Baseline.isLong(it) && base.serial != dismissed.longBaselineSerial },
                change = base.change?.takeIf { it.count != dismissed.changeCount },
                baseSerial = base.serial,
            )
        }
    }
}

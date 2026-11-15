package es.topophone.app.ui.receiver

import androidx.annotation.StringRes
import es.topophone.app.R
import es.topophone.core.gnss.nmea.ReplaySpeed
import es.topophone.core.gnss.scenario.ScenarioId
import es.topophone.core.gnss.scenario.ScenarioOrigin
import es.topophone.core.model.project.CentralPoint

/** Dónde ocurre el escenario del receptor virtual: en el centro del proyecto o en unas coordenadas (S022/RF-13). */
enum class VirtualOriginMode { PROJECT, COORDINATES }

/**
 * La elección del receptor virtual en curso (S022/RF-13, RF-16): escenario, origen y velocidad. El origen por defecto es el centro
 * del proyecto más reciente (S006/RF-37); sin proyecto o sin centro hay que escribir unas coordenadas, y sin ellas no se puede
 * continuar.
 */
data class VirtualUi(
    val scenario: ScenarioId = ScenarioId.STABLE_FIX,
    val speed: ReplaySpeed = ReplaySpeed.X1,
    val projectName: String? = null,
    val projectCenter: CentralPoint? = null,
    val originMode: VirtualOriginMode = VirtualOriginMode.COORDINATES,
    val latitudeText: String = "",
    val longitudeText: String = "",
) {
    /** El origen elegido, o null si falta o no es válido. */
    fun origin(): ScenarioOrigin? = when (originMode) {
        VirtualOriginMode.PROJECT -> projectCenter?.let { ScenarioOrigin(it.latitudeDeg, it.longitudeDeg) }
        VirtualOriginMode.COORDINATES -> VirtualOriginText.parse(latitudeText, longitudeText)
    }

    val canContinue: Boolean get() = origin() != null

    val latitudeInvalid: Boolean get() = latitudeText.isNotEmpty() && VirtualOriginText.latitude(latitudeText) == null

    val longitudeInvalid: Boolean get() = longitudeText.isNotEmpty() && VirtualOriginText.longitude(longitudeText) == null

    companion object {
        /** La elección de partida: el centro del proyecto si lo hay y, si no, coordenadas por escribir (S022/RF-13). */
        fun initial(projectName: String?, center: CentralPoint?) = VirtualUi(
            projectName = projectName,
            projectCenter = center,
            originMode = if (center != null) VirtualOriginMode.PROJECT else VirtualOriginMode.COORDINATES,
        )
    }
}

/** Las coordenadas escritas a mano para el origen del receptor virtual: grados decimales, con punto o coma (S022/RF-13). */
object VirtualOriginText {
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0

    fun latitude(text: String): Double? = number(text)?.takeIf { it in -MAX_LATITUDE..MAX_LATITUDE }

    fun longitude(text: String): Double? = number(text)?.takeIf { it in -MAX_LONGITUDE..MAX_LONGITUDE }

    fun parse(latitudeText: String, longitudeText: String): ScenarioOrigin? {
        val latitude = latitude(latitudeText) ?: return null
        val longitude = longitude(longitudeText) ?: return null
        return ScenarioOrigin(latitude, longitude)
    }

    private fun number(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}

/** El nombre de un escenario, como lo ve el usuario (S022/RF-14, RNF-T7). */
@StringRes
fun ScenarioId.nameRes(): Int = when (this) {
    ScenarioId.STABLE_FIX -> R.string.scenario_stable_fix
    ScenarioId.FIX_LOSS_AND_RECOVERY -> R.string.scenario_fix_loss
    ScenarioId.FLOAT_AND_SINGLE -> R.string.scenario_float_single
    ScenarioId.DATA_GAP -> R.string.scenario_data_gap
    ScenarioId.BASE_CHANGE -> R.string.scenario_base_change
    ScenarioId.TILT_FIGURE_EIGHT -> R.string.scenario_tilt
    ScenarioId.STRAIGHT_WALK -> R.string.scenario_walk
}

/** La descripción de un escenario (S022/RF-14). */
@StringRes
fun ScenarioId.descriptionRes(): Int = when (this) {
    ScenarioId.STABLE_FIX -> R.string.scenario_stable_fix_description
    ScenarioId.FIX_LOSS_AND_RECOVERY -> R.string.scenario_fix_loss_description
    ScenarioId.FLOAT_AND_SINGLE -> R.string.scenario_float_single_description
    ScenarioId.DATA_GAP -> R.string.scenario_data_gap_description
    ScenarioId.BASE_CHANGE -> R.string.scenario_base_change_description
    ScenarioId.TILT_FIGURE_EIGHT -> R.string.scenario_tilt_description
    ScenarioId.STRAIGHT_WALK -> R.string.scenario_walk_description
}

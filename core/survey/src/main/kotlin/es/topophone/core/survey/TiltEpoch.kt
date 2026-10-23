package es.topophone.core.survey

import es.topophone.core.gnss.nmea.TiltData
import es.topophone.core.gnss.nmea.TiltNotification
import es.topophone.core.gnss.nmea.TiltState
import es.topophone.core.model.point.PoleVector
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Qué hacer cuando la inclinación no está lista: una fila de la tabla de S016/RF-12. */
enum class TiltGuide { NO_TILT, FATAL, SETUP, ALIGNMENT, FAST_MOTION, BAD_GNSS, FILTER_FAULT }

/** Precisión de la inclinación en planta y en vertical, en metros (ETC_rev_3; S016/RF-21). */
data class TiltAccuracy(val horizontalM: Double, val verticalM: Double)

/** Lo que dice el ETC de una época (S016, plan A2). */
object TiltEpoch {
    private const val MM_PER_M = 1000.0

    /** "En estado 30": compensando, con la inclinación, su dirección y los tres coeficientes (términos de S016). */
    fun isCompensating(tilt: TiltData?): Boolean = tilt != null &&
        tilt.state == TiltState.COMPENSATING &&
        tilt.tiltRad != null &&
        tilt.tiltDirectionRad != null &&
        tilt.accuracyCoefficients != null

    /** En estado 30 y sin aviso: la única que vale para medir con la inclinación (S016/RF-18, RF-19). */
    fun isClean(tilt: TiltData?): Boolean = isCompensating(tilt) && tilt?.notification == TiltNotification.NONE

    /** La guía que toca, o null si la época está limpia (S016/RF-12). */
    fun guide(tilt: TiltData?): TiltGuide? = when {
        tilt == null || tilt.state == TiltState.OFF -> TiltGuide.NO_TILT
        tilt.state == TiltState.FATAL -> TiltGuide.FATAL
        tilt.state == TiltState.SETUP -> TiltGuide.SETUP
        tilt.state == TiltState.ALIGNMENT -> TiltGuide.ALIGNMENT
        !isCompensating(tilt) -> TiltGuide.SETUP // estado 30 sin algún campo: esperar
        else -> when (tilt.notification) {
            TiltNotification.NONE -> null
            TiltNotification.FAST_MOTION -> TiltGuide.FAST_MOTION
            TiltNotification.BAD_GNSS -> TiltGuide.BAD_GNSS
            TiltNotification.FILTER_FAULT -> TiltGuide.FILTER_FAULT
            TiltNotification.OFF -> TiltGuide.SETUP // aviso imposible en estado 30: esperar
        }
    }

    /** Vector unitario del jalón, de la punta a la antena (ETC_rev_3: Δ = −H·u); null fuera del estado 30. */
    fun poleVector(tilt: TiltData?): PoleVector? {
        if (tilt == null || !isCompensating(tilt)) return null
        val theta = checkNotNull(tilt.tiltRad)
        val alpha = checkNotNull(tilt.tiltDirectionRad)
        return PoleVector(sin(theta) * sin(alpha), sin(theta) * cos(alpha), cos(theta))
    }

    /** Coeficiente × altura de antena en metros = milímetros, por eje; en planta, la raíz de este y norte (ETC_rev_3). */
    fun accuracy(tilt: TiltData?, antennaHeightM: Double): TiltAccuracy? {
        val c = tilt?.accuracyCoefficients ?: return null
        return TiltAccuracy(
            horizontalM = hypot(c.east, c.north) * antennaHeightM / MM_PER_M,
            verticalM = c.up * antennaHeightM / MM_PER_M,
        )
    }
}

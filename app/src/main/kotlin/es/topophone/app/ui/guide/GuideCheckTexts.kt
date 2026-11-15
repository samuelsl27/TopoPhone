package es.topophone.app.ui.guide

import androidx.annotation.StringRes
import es.topophone.app.R
import es.topophone.core.model.guide.CheckCause
import es.topophone.core.model.guide.CheckId
import es.topophone.core.model.guide.CheckStatus

/**
 * Los textos de las comprobaciones (S043/RF-6 a RF-10, RNF-T7): el nombre de cada una, el estado y la causa probable. Se escriben a mano,
 * sin buscar los recursos por nombre, para que Lint y el compilador vean que se usan y avisen si falta alguno.
 */
object GuideCheckTexts {

    @StringRes
    fun title(id: CheckId): Int = when (id) {
        CheckId.GGA_RATE -> R.string.guide_check_gga_rate
        CheckId.SENTENCES -> R.string.guide_check_sentences
        CheckId.SOLUTION -> R.string.guide_check_solution
        CheckId.CORRECTION_AGE -> R.string.guide_check_correction_age
        CheckId.UTC_TIME -> R.string.guide_check_utc_time
        CheckId.TILT -> R.string.guide_check_tilt
        CheckId.RTCM_BASE -> R.string.guide_check_rtcm_base
        CheckId.RTCM_OBSERVATIONS -> R.string.guide_check_rtcm_observations
        CheckId.RTCM_GLONASS -> R.string.guide_check_rtcm_glonass
        CheckId.DATUM -> R.string.guide_check_datum
    }

    @StringRes
    fun status(status: CheckStatus): Int = when (status) {
        CheckStatus.OK -> R.string.guide_status_ok
        CheckStatus.WARNING -> R.string.guide_status_warning
        CheckStatus.FAIL -> R.string.guide_status_fail
        CheckStatus.NO_DATA -> R.string.guide_status_no_data
    }

    /** El símbolo de cada estado: junto a su texto, para que no se distinga solo por el color (RNF-T8). */
    fun icon(status: CheckStatus): String = when (status) {
        CheckStatus.OK -> "✔"
        CheckStatus.WARNING -> "⚠"
        CheckStatus.FAIL -> "✖"
        CheckStatus.NO_DATA -> "…"
    }

    @StringRes
    fun cause(cause: CheckCause): Int = when (cause) {
        CheckCause.NO_DATA -> R.string.guide_cause_no_data
        CheckCause.NO_GGA -> R.string.guide_cause_no_gga
        CheckCause.GGA_BELOW_1HZ -> R.string.guide_cause_gga_below_1hz
        CheckCause.GGA_SLOWER_THAN_GNSS -> R.string.guide_cause_gga_slower_than_gnss
        CheckCause.MISSING_GST -> R.string.guide_cause_missing_gst
        CheckCause.MISSING_GSV -> R.string.guide_cause_missing_gsv
        CheckCause.MISSING_ZDA -> R.string.guide_cause_missing_zda
        CheckCause.MISSING_RMC -> R.string.guide_cause_missing_rmc
        CheckCause.NO_POSITION -> R.string.guide_cause_no_position
        CheckCause.SOLUTION_NOT_FIX -> R.string.guide_cause_solution_not_fix
        CheckCause.CORRECTIONS_OLD -> R.string.guide_cause_corrections_old
        CheckCause.CORRECTIONS_STALE -> R.string.guide_cause_corrections_stale
        CheckCause.NO_TIME -> R.string.guide_cause_no_time
        CheckCause.TIME_NOT_ADVANCING -> R.string.guide_cause_time_not_advancing
        CheckCause.TILT_NOT_COMPENSATING -> R.string.guide_cause_tilt_not_compensating
        CheckCause.TILT_NOTICE -> R.string.guide_cause_tilt_notice
        CheckCause.TILT_MODE_UNSET -> R.string.guide_cause_tilt_mode_unset
        CheckCause.TILT_MODE_UNVERIFIED -> R.string.guide_cause_tilt_mode_unverified
        CheckCause.NO_BASE_MESSAGES -> R.string.guide_cause_no_base_messages
        CheckCause.NO_OBSERVATIONS -> R.string.guide_cause_no_observations
        CheckCause.NO_GLONASS -> R.string.guide_cause_no_glonass
        CheckCause.DATUM_MISMATCH -> R.string.guide_cause_datum_mismatch
        CheckCause.DATUM_UNKNOWN -> R.string.guide_cause_datum_unknown
    }
}

package es.topophone.app.ui.guide

import androidx.annotation.StringRes
import es.topophone.app.R
import es.topophone.core.model.guide.ConfigListId

/**
 * Los textos de las listas de configuración (S043/RF-1, RF-2, RNF-T7): para cada lista y cada paso, su recurso. Se escribe a mano,
 * sin buscar los recursos por nombre, para que Lint y el compilador vean que se usan y avisen si falta alguno.
 */
object GuideTexts {

    /** La nota de firmware con que se redactaron las listas: sin número de versión (S043/RF-2). */
    @StringRes
    fun firmwareNote(): Int = R.string.guide_firmware_note

    /** El título de una lista. */
    @StringRes
    fun listTitle(id: ConfigListId): Int = when (id) {
        ConfigListId.NTRIP_ROVER -> R.string.guide_list_ntrip_rover_title
        ConfigListId.WIFI_ROVER -> R.string.guide_list_wifi_rover_title
        ConfigListId.TILT_ROVER -> R.string.guide_list_tilt_rover_title
        ConfigListId.BASE -> R.string.guide_list_base_title
    }

    /** La descripción de una lista. */
    @StringRes
    fun listSummary(id: ConfigListId): Int = when (id) {
        ConfigListId.NTRIP_ROVER -> R.string.guide_list_ntrip_rover_summary
        ConfigListId.WIFI_ROVER -> R.string.guide_list_wifi_rover_summary
        ConfigListId.TILT_ROVER -> R.string.guide_list_tilt_rover_summary
        ConfigListId.BASE -> R.string.guide_list_base_summary
    }

    /** El título de un paso. */
    @StringRes
    fun stepTitle(stepId: String): Int = when (stepId) {
        "ntrip_output" -> R.string.guide_step_ntrip_output_title
        "ntrip_rate" -> R.string.guide_step_ntrip_rate_title
        "ntrip_messages" -> R.string.guide_step_ntrip_messages_title
        "ntrip_constellations" -> R.string.guide_step_ntrip_constellations_title
        "ntrip_mode" -> R.string.guide_step_ntrip_mode_title
        "ntrip_time" -> R.string.guide_step_ntrip_time_title
        "ntrip_corrections" -> R.string.guide_step_ntrip_corrections_title
        "ntrip_datum" -> R.string.guide_step_ntrip_datum_title
        "ntrip_fix" -> R.string.guide_step_ntrip_fix_title
        "wifi_access" -> R.string.guide_step_wifi_access_title
        "wifi_address" -> R.string.guide_step_wifi_address_title
        "wifi_tcp" -> R.string.guide_step_wifi_tcp_title
        "wifi_corrections_port" -> R.string.guide_step_wifi_corrections_port_title
        "wifi_time" -> R.string.guide_step_wifi_time_title
        "wifi_messages" -> R.string.guide_step_wifi_messages_title
        "tilt_imu" -> R.string.guide_step_tilt_imu_title
        "tilt_etc" -> R.string.guide_step_tilt_etc_title
        "tilt_mode" -> R.string.guide_step_tilt_mode_title
        "tilt_rate" -> R.string.guide_step_tilt_rate_title
        "tilt_gear" -> R.string.guide_step_tilt_gear_title
        "base_coordinates" -> R.string.guide_step_base_coordinates_title
        "base_messages" -> R.string.guide_step_base_messages_title
        "base_observations" -> R.string.guide_step_base_observations_title
        "base_output" -> R.string.guide_step_base_output_title
        "base_datum" -> R.string.guide_step_base_datum_title
        else -> error("Paso desconocido: $stepId")
    }

    /** Qué ajuste cambiar en el receptor y dónde, para un paso. */
    @StringRes
    fun stepDetail(stepId: String): Int = when (stepId) {
        "ntrip_output" -> R.string.guide_step_ntrip_output_detail
        "ntrip_rate" -> R.string.guide_step_ntrip_rate_detail
        "ntrip_messages" -> R.string.guide_step_ntrip_messages_detail
        "ntrip_constellations" -> R.string.guide_step_ntrip_constellations_detail
        "ntrip_mode" -> R.string.guide_step_ntrip_mode_detail
        "ntrip_time" -> R.string.guide_step_ntrip_time_detail
        "ntrip_corrections" -> R.string.guide_step_ntrip_corrections_detail
        "ntrip_datum" -> R.string.guide_step_ntrip_datum_detail
        "ntrip_fix" -> R.string.guide_step_ntrip_fix_detail
        "wifi_access" -> R.string.guide_step_wifi_access_detail
        "wifi_address" -> R.string.guide_step_wifi_address_detail
        "wifi_tcp" -> R.string.guide_step_wifi_tcp_detail
        "wifi_corrections_port" -> R.string.guide_step_wifi_corrections_port_detail
        "wifi_time" -> R.string.guide_step_wifi_time_detail
        "wifi_messages" -> R.string.guide_step_wifi_messages_detail
        "tilt_imu" -> R.string.guide_step_tilt_imu_detail
        "tilt_etc" -> R.string.guide_step_tilt_etc_detail
        "tilt_mode" -> R.string.guide_step_tilt_mode_detail
        "tilt_rate" -> R.string.guide_step_tilt_rate_detail
        "tilt_gear" -> R.string.guide_step_tilt_gear_detail
        "base_coordinates" -> R.string.guide_step_base_coordinates_detail
        "base_messages" -> R.string.guide_step_base_messages_detail
        "base_observations" -> R.string.guide_step_base_observations_detail
        "base_output" -> R.string.guide_step_base_output_detail
        "base_datum" -> R.string.guide_step_base_datum_detail
        else -> error("Paso desconocido: $stepId")
    }
}

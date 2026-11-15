package es.topophone.core.model.guide

/**
 * Las cuatro listas de configuración del receptor (S043/RF-1, plan D5). Son el contenido inicial: cada paso dice qué ajuste cambiar y
 * en qué pantalla del receptor, con textos propios de TopoPhone en los recursos de la app (`guide_step_<id>_title` y `_detail`); los
 * pasos con comprobaciones se pueden verificar con lo que llega por NMEA y RTCM (RF-6 a RF-8). Cada comprobación va en un solo paso de
 * cada lista: el que la resuelve (RF-9). Nada de esto escribe en el receptor (RF-11).
 */
object ConfigLists {

    val ntripRover = ConfigList(
        ConfigListId.NTRIP_ROVER,
        listOf(
            ConfigStep("ntrip_output"),
            ConfigStep("ntrip_rate", listOf(CheckId.GGA_RATE)),
            ConfigStep("ntrip_messages", listOf(CheckId.SENTENCES)),
            ConfigStep("ntrip_constellations"),
            ConfigStep("ntrip_mode"),
            ConfigStep("ntrip_time", listOf(CheckId.UTC_TIME)),
            ConfigStep(
                "ntrip_corrections",
                listOf(CheckId.CORRECTION_AGE, CheckId.RTCM_BASE, CheckId.RTCM_OBSERVATIONS, CheckId.RTCM_GLONASS),
            ),
            ConfigStep("ntrip_datum", listOf(CheckId.DATUM)),
            ConfigStep("ntrip_fix", listOf(CheckId.SOLUTION)),
        ),
    )

    val wifiRover = ConfigList(
        ConfigListId.WIFI_ROVER,
        listOf(
            ConfigStep("wifi_access"),
            ConfigStep("wifi_address"),
            ConfigStep("wifi_tcp", listOf(CheckId.GGA_RATE)),
            ConfigStep("wifi_corrections_port"),
            ConfigStep("wifi_time", listOf(CheckId.UTC_TIME)),
            ConfigStep("wifi_messages", listOf(CheckId.SENTENCES)),
        ),
    )

    val tiltRover = ConfigList(
        ConfigListId.TILT_ROVER,
        listOf(
            ConfigStep("tilt_imu"),
            ConfigStep("tilt_etc", listOf(CheckId.TILT)),
            ConfigStep("tilt_mode"),
            ConfigStep("tilt_rate", listOf(CheckId.GGA_RATE)),
            ConfigStep("tilt_gear"),
        ),
    )

    val base = ConfigList(
        ConfigListId.BASE,
        listOf(
            ConfigStep("base_coordinates"),
            ConfigStep("base_messages", listOf(CheckId.RTCM_BASE)),
            ConfigStep("base_observations", listOf(CheckId.RTCM_OBSERVATIONS)),
            ConfigStep("base_output"),
            ConfigStep("base_datum", listOf(CheckId.DATUM)),
        ),
    )

    /** Las listas, en el orden en que se ofrecen (RF-1). */
    val all: List<ConfigList> = listOf(ntripRover, wifiRover, tiltRover, base)

    fun byId(id: ConfigListId): ConfigList = all.first { it.id == id }

    /** El paso de la lista [listId] que resuelve la comprobación [check] (RF-9), o `null` si esa lista no la lleva. */
    fun stepFor(listId: ConfigListId, check: CheckId): String? = byId(listId).steps.firstOrNull { check in it.checks }?.id

    /** Las claves de los textos de un paso en los recursos de la app (RNF-T7). */
    fun titleKey(stepId: String) = "guide_step_${stepId}_title"

    fun detailKey(stepId: String) = "guide_step_${stepId}_detail"

    /** Las claves de los textos de una lista en los recursos de la app. */
    fun listTitleKey(id: ConfigListId) = "guide_list_${id.name.lowercase()}_title"

    fun listSummaryKey(id: ConfigListId) = "guide_list_${id.name.lowercase()}_summary"
}

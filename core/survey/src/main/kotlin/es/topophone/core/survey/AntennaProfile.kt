package es.topophone.core.survey

/**
 * Perfil de antena (S046): un modelo de receptor o de antena con su desplazamiento al centro de fase y, si se conoce, el
 * radio de su base.
 *
 * @property ref `catalog:<modelo>` para el catálogo y `own:<uuid>` para los propios (S046, plan).
 * @property offsetM distancia vertical del ARP (en la base del receptor) al centro de fase de la antena (S046/RF-1, RF-3).
 * @property radiusM radio de la base para la medida inclinada; null si no se conoce (S046/RF-3, RF-17).
 */
data class AntennaProfile(val ref: String, val name: String, val offsetM: Double, val radiusM: Double?, val builtIn: Boolean) {
    companion object {
        /** Reach RS3: 0,134 m según ETC_rev_3 de Emlid (se valida en G0; S002/RF-32, S046/RF-2). */
        val RS3: AntennaProfile get() = AntennaCatalog.RS3
    }
}

/**
 * Modelos Reach con su desplazamiento al centro de fase, de la documentación de Emlid (S046/RF-1). Emlid no publica el
 * radio de la base, así que ninguno lo trae: para la medida inclinada hay que crear un perfil propio con radio (S046/RF-5).
 */
object AntennaCatalog {
    val RS3 = AntennaProfile("catalog:RS3", "Reach RS3", 0.134, null, builtIn = true)
    private val RS2 = AntennaProfile("catalog:RS2", "Reach RS2/RS2+", 0.134, null, builtIn = true)
    private val RS = AntennaProfile("catalog:RS", "Reach RS/RS+", 0.065, null, builtIn = true)
    private val RS4 = AntennaProfile("catalog:RS4", "Reach RS4/RS4 Pro", 0.095, null, builtIn = true)
    private val RX = AntennaProfile("catalog:RX", "Reach RX", 0.1471, null, builtIn = true)

    val ALL: List<AntennaProfile> = listOf(RS3, RS2, RS, RS4, RX)

    fun byRef(ref: String): AntennaProfile? = ALL.firstOrNull { it.ref == ref }
}

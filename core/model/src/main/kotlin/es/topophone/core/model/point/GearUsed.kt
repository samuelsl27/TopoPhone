package es.topophone.core.model.point

/** Cómo se mide la altura de un jalón: en vertical o inclinada hasta el borde de la base del receptor (S046/RF-16). */
enum class PoleKind { VERTICAL, SLANT }

/**
 * El jalón y la antena con los que se midió un punto (S046/RF-24): una copia de sus datos, para que editar o borrar
 * después el jalón o el perfil no cambie ningún punto (constitución nº 1). La altura vertical del jalón está en
 * [Measurement.poleHeightM].
 *
 * @property poleEnteredM lo que escribió el usuario: la altura vertical o la inclinada, según [poleKind].
 * @property antennaProfile el nombre del perfil de antena.
 * @property antennaOffsetM su desplazamiento del ARP al centro de fase.
 * @property antennaHeightM la altura de antena: la vertical del jalón más [antennaOffsetM].
 */
data class GearUsed(
    val poleName: String,
    val poleKind: PoleKind,
    val poleEnteredM: Double,
    val antennaProfile: String,
    val antennaOffsetM: Double,
    val antennaHeightM: Double,
)

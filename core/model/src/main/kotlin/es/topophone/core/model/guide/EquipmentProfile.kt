package es.topophone.core.model.guide

/**
 * Perfil de equipo de un receptor: su modelo, su número de serie y la versión de su firmware, tal como los escribe el usuario
 * (S043/RF-18). En la interfaz es la entrada «Datos del receptor». Se guarda con cada punto medido (RF-19).
 */
data class EquipmentProfile(val model: String = "", val serial: String = "", val firmware: String = "") {

    /** Sin ningún dato: no se guarda nada con el punto. */
    val isBlank: Boolean get() = model.isBlank() && serial.isBlank() && firmware.isBlank()

    /** Sin espacios en los extremos de cada campo. */
    fun trimmed() = EquipmentProfile(model.trim(), serial.trim(), firmware.trim())

    companion object {
        val EMPTY = EquipmentProfile()
    }
}

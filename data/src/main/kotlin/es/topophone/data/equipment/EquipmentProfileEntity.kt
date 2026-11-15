package es.topophone.data.equipment

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Los datos de un receptor que escribe el usuario (S043/RF-18, plan D6): modelo, número de serie y firmware, y la dirección de su
 * interfaz web si la ha guardado (RF-13). Una fila por clave de receptor (la de S021); no es de ningún proyecto.
 */
@Entity(tableName = "equipment_profiles")
data class EquipmentProfileEntity(
    @PrimaryKey @ColumnInfo(name = "receiver_key") val receiverKey: String,
    val model: String,
    val serial: String,
    val firmware: String,
    @ColumnInfo(name = "web_address") val webAddress: String? = null,
)

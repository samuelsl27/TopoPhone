package es.topophone.data.receiver

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un receptor Wi-Fi guardado (S021/RF-4, plan D2): su `id` es su identidad y no cambia al editar la dirección o los puertos (RF-5);
 * `corrections_port` es null si el receptor no tiene puerto de entrada de correcciones (RF-12). No es de ningún proyecto.
 */
@Entity(tableName = "wifi_receivers")
data class WifiReceiverEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    @ColumnInfo(name = "position_port") val positionPort: Int,
    @ColumnInfo(name = "corrections_port") val correctionsPort: Int?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

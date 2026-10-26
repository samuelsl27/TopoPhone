package es.topophone.data.gear

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un perfil de antena propio (S046/RF-3). Los del catálogo no tienen fila: viven en el código (plan). [nameKey] es el
 * nombre sin espacios, mayúsculas ni tildes, con índice único (S046/RF-4).
 */
@Entity(tableName = "antenna_profiles", indices = [Index(value = ["name_key"], unique = true)])
data class AntennaProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "offset_m") val offsetM: Double,
    @ColumnInfo(name = "radius_m") val radiusM: Double?,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
)

/** Un jalón guardado (S046/RF-9). [kind] es `VERTICAL` o `SLANT` y [enteredM] lo escrito (S046/RF-16). */
@Entity(tableName = "saved_poles", indices = [Index(value = ["name_key"], unique = true)])
data class SavedPoleEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    val kind: String,
    @ColumnInfo(name = "entered_m") val enteredM: Double,
    @ColumnInfo(name = "created_ms") val createdMs: Long,
)

/**
 * El equipo que recuerda un receptor (S046/RF-7, RF-13): [receiverKey] es su dirección Bluetooth o `replay`, [profileRef]
 * `catalog:<modelo>` o `own:<uuid>`, y [activePoleId] el jalón activo. Un campo nulo usa el valor por defecto.
 */
@Entity(tableName = "receiver_gear")
data class ReceiverGearEntity(
    @PrimaryKey @ColumnInfo(name = "receiver_key") val receiverKey: String,
    @ColumnInfo(name = "profile_ref") val profileRef: String?,
    @ColumnInfo(name = "active_pole_id") val activePoleId: String?,
)

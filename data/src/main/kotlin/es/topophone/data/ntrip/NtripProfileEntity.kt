package es.topophone.data.ntrip

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un perfil NTRIP (S007/RF-1, RF-2). La contraseña solo se guarda cifrada (RNF-T11): IV y texto
 * cifrado con AES-GCM, los dos nulos si no hay contraseña. `mountpoint_needs_gga` es nulo cuando no
 * se sabe (punto de montaje escrito a mano, S007/RF-21). Fechas en ms UTC.
 */
@Entity(tableName = "ntrip_profiles")
class NtripProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    @ColumnInfo(name = "use_tls") val useTls: Boolean,
    val username: String,
    val mountpoint: String,
    @ColumnInfo(name = "mountpoint_needs_gga") val mountpointNeedsGga: Boolean?,
    @ColumnInfo(name = "password_iv") val passwordIv: ByteArray?,
    @ColumnInfo(name = "password_ct") val passwordCiphertext: ByteArray?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
)

package es.topophone.data.config

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Un paso de una lista de configuración que el usuario marcó como hecho (S043/RF-3, plan D6): una fila por paso hecho, para un receptor
 * (su clave, la de S021) y una lista. Desmarcar un paso borra su fila y reiniciar una lista borra las suyas. No es de ningún proyecto.
 */
@Entity(tableName = "config_steps", primaryKeys = ["receiver_key", "list_id", "step_id"])
data class ConfigStepEntity(
    @ColumnInfo(name = "receiver_key") val receiverKey: String,
    @ColumnInfo(name = "list_id") val listId: String,
    @ColumnInfo(name = "step_id") val stepId: String,
    @ColumnInfo(name = "done_at") val doneAt: Long,
)

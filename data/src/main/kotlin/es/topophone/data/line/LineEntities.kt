package es.topophone.data.line

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import es.topophone.data.point.PointEntity
import es.topophone.data.project.ProjectEntity

/**
 * Una línea del proyecto (S025, plan D1 a D3). El código va por valor, como en los puntos (S024/D3); [geometry] es la del código al
 * crearla (LINE o POLYGON). El nombre es único entre las líneas activas de un proyecto: `trash_key` vale '' en ellas y el id en las de la
 * papelera, que así dejan libre su nombre. Como mucho una línea del proyecto tiene `active` a 1 (lo mantiene el repositorio).
 */
@Entity(
    tableName = "project_lines",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id", "name", "trash_key"], unique = true)],
)
data class ProjectLineEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val name: String,
    @ColumnInfo(defaultValue = "'LINE'") val geometry: String,
    val closed: Boolean,
    val hidden: Boolean,
    val active: Boolean,
    val code: String?,
    @ColumnInfo(name = "code_description") val codeDescription: String?,
    @ColumnInfo(name = "code_color") val codeColor: Int?,
    @ColumnInfo(name = "code_symbol") val codeSymbol: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    @ColumnInfo(name = "trash_key", defaultValue = "''") val trashKey: String = "",
)

/**
 * Un vértice de una línea: el punto del proyecto que referencia (S025/D1, D7). Si el punto se borra del todo, el vértice desaparece con él;
 * si solo está en la papelera, el vértice se queda y la lectura de la línea lo omite.
 */
@Entity(
    tableName = "line_vertices",
    primaryKeys = ["line_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = ProjectLineEntity::class, parentColumns = ["id"], childColumns = ["line_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["point_id"])],
)
data class LineVertexEntity(
    @ColumnInfo(name = "line_id") val lineId: String,
    val ordinal: Int,
    @ColumnInfo(name = "point_id") val pointId: String,
)

/** El valor de un atributo del código de una línea, en texto canónico (S025/D2). */
@Entity(
    tableName = "line_attributes",
    primaryKeys = ["line_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = ProjectLineEntity::class, parentColumns = ["id"], childColumns = ["line_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class LineAttributeEntity(
    @ColumnInfo(name = "line_id") val lineId: String,
    val ordinal: Int,
    val name: String,
    val type: String,
    val value: String,
)

/** Una versión anterior de una línea con la fecha del cambio (S025/RF-18, D6). Se borra con la línea. */
@Entity(
    tableName = "line_history",
    foreignKeys = [
        ForeignKey(entity = ProjectLineEntity::class, parentColumns = ["id"], childColumns = ["line_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["line_id"])],
)
data class LineHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "line_id") val lineId: String,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
    val change: String,
    val name: String,
    val closed: Boolean,
    val code: String?,
    @ColumnInfo(name = "code_description") val codeDescription: String?,
    @ColumnInfo(name = "code_color") val codeColor: Int?,
    @ColumnInfo(name = "code_symbol") val codeSymbol: String?,
)

/** Los valores de los atributos que tenía una línea en una versión del historial (S025/D6). */
@Entity(
    tableName = "line_history_attributes",
    primaryKeys = ["history_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = LineHistoryEntity::class, parentColumns = ["id"], childColumns = ["history_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class LineHistoryAttributeEntity(
    @ColumnInfo(name = "history_id") val historyId: String,
    val ordinal: Int,
    val name: String,
    val type: String,
    val value: String,
)

/** Los vértices que tenía una línea en una versión del historial, con el nombre del punto entonces y sin clave foránea (S025/D6). */
@Entity(
    tableName = "line_history_vertices",
    primaryKeys = ["history_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = LineHistoryEntity::class, parentColumns = ["id"], childColumns = ["history_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class LineHistoryVertexEntity(
    @ColumnInfo(name = "history_id") val historyId: String,
    val ordinal: Int,
    @ColumnInfo(name = "point_id") val pointId: String,
    @ColumnInfo(name = "point_name") val pointName: String,
)

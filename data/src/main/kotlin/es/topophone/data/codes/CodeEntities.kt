package es.topophone.data.codes

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import es.topophone.data.project.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * Una biblioteca de códigos (S023, plan D1). Con [projectId] NULL es una biblioteca del usuario; con él es la copia que lleva ese proyecto
 * (como mucho una por proyecto, que se borra con él). [nameKey] es la clave de comparación del nombre; [sourceName] es el nombre de la
 * biblioteca de la que viene una copia, solo informativo.
 */
@Entity(
    tableName = "code_libraries",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id"], unique = true), Index("name_key")],
)
data class CodeLibraryEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "project_id") val projectId: String?,
    @ColumnInfo(name = "source_name") val sourceName: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/** Un código de una biblioteca; [codeKey] es único en ella (S023/RF-6). [geometry] es `POINT`, `LINE` o `POLYGON`; [color], 0xRRGGBB. */
@Entity(
    tableName = "library_codes",
    foreignKeys = [
        ForeignKey(entity = CodeLibraryEntity::class, parentColumns = ["id"], childColumns = ["library_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["library_id", "code_key"], unique = true)],
)
data class LibraryCodeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "library_id") val libraryId: String,
    val code: String,
    @ColumnInfo(name = "code_key") val codeKey: String,
    val description: String,
    val geometry: String,
    val color: Int,
)

/** Un atributo de un código, en su orden. [type] es `TEXT`, `INTEGER`, `NUMBER` o `LIST`; [defaultValue] es texto canónico (D7). */
@Entity(
    tableName = "code_attributes",
    foreignKeys = [
        ForeignKey(entity = LibraryCodeEntity::class, parentColumns = ["id"], childColumns = ["code_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("code_id")],
)
data class CodeAttributeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "code_id") val codeId: String,
    val ordinal: Int,
    val name: String,
    val type: String,
    @ColumnInfo(name = "default_value") val defaultValue: String?,
)

/** Una opción de un atributo de tipo Lista, en su orden. */
@Entity(
    tableName = "attribute_options",
    primaryKeys = ["attribute_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = CodeAttributeEntity::class,
            parentColumns = ["id"],
            childColumns = ["attribute_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class AttributeOptionEntity(@ColumnInfo(name = "attribute_id") val attributeId: String, val ordinal: Int, val value: String)

@Dao
interface CodeLibraryDao {
    @Insert
    suspend fun insertLibrary(library: CodeLibraryEntity)

    @Insert
    suspend fun insertCode(code: LibraryCodeEntity)

    @Insert
    suspend fun insertAttribute(attribute: CodeAttributeEntity)

    @Insert
    suspend fun insertOption(option: AttributeOptionEntity)

    @Query("SELECT * FROM code_libraries WHERE project_id IS NULL ORDER BY name_key")
    fun userLibraries(): Flow<List<CodeLibraryEntity>>

    @Query("SELECT * FROM code_libraries WHERE project_id = :projectId")
    suspend fun copyOf(projectId: String): CodeLibraryEntity?

    @Query("SELECT * FROM library_codes WHERE library_id = :libraryId ORDER BY code_key")
    suspend fun codes(libraryId: String): List<LibraryCodeEntity>
}

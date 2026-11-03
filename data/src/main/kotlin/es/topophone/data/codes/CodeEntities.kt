package es.topophone.data.codes

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
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

/** Cuántos códigos tiene una biblioteca (S023/RF-1). */
data class LibraryCodeCount(@ColumnInfo(name = "library_id") val libraryId: String, @ColumnInfo(name = "n") val count: Int)

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

    @Query("SELECT name FROM code_libraries WHERE project_id IS NULL AND id != :exceptId")
    suspend fun userLibraryNames(exceptId: String = ""): List<String>

    @Query("SELECT library_id, COUNT(*) AS n FROM library_codes GROUP BY library_id")
    fun codeCounts(): Flow<List<LibraryCodeCount>>

    @Query("SELECT * FROM code_libraries WHERE id = :id")
    suspend fun library(id: String): CodeLibraryEntity?

    @Query("SELECT * FROM code_libraries WHERE id = :id")
    fun observeLibrary(id: String): Flow<CodeLibraryEntity?>

    @Query("SELECT * FROM code_libraries WHERE project_id = :projectId")
    suspend fun copyOf(projectId: String): CodeLibraryEntity?

    @Query("SELECT * FROM code_libraries WHERE project_id = :projectId")
    fun observeCopyOf(projectId: String): Flow<CodeLibraryEntity?>

    @Query("SELECT * FROM library_codes WHERE library_id = :libraryId ORDER BY code_key")
    suspend fun codes(libraryId: String): List<LibraryCodeEntity>

    @Query("SELECT * FROM library_codes WHERE library_id = :libraryId ORDER BY code_key")
    fun observeCodes(libraryId: String): Flow<List<LibraryCodeEntity>>

    @Query(
        "SELECT a.* FROM code_attributes a JOIN library_codes c ON c.id = a.code_id " +
            "WHERE c.library_id = :libraryId ORDER BY a.code_id, a.ordinal",
    )
    suspend fun attributes(libraryId: String): List<CodeAttributeEntity>

    @Query(
        "SELECT a.* FROM code_attributes a JOIN library_codes c ON c.id = a.code_id " +
            "WHERE c.library_id = :libraryId ORDER BY a.code_id, a.ordinal",
    )
    fun observeAttributes(libraryId: String): Flow<List<CodeAttributeEntity>>

    @Query(
        "SELECT o.* FROM attribute_options o JOIN code_attributes a ON a.id = o.attribute_id " +
            "JOIN library_codes c ON c.id = a.code_id WHERE c.library_id = :libraryId ORDER BY o.attribute_id, o.ordinal",
    )
    suspend fun options(libraryId: String): List<AttributeOptionEntity>

    @Query(
        "SELECT o.* FROM attribute_options o JOIN code_attributes a ON a.id = o.attribute_id " +
            "JOIN library_codes c ON c.id = a.code_id WHERE c.library_id = :libraryId ORDER BY o.attribute_id, o.ordinal",
    )
    fun observeOptions(libraryId: String): Flow<List<AttributeOptionEntity>>

    @Update
    suspend fun updateCode(code: LibraryCodeEntity)

    @Query("SELECT * FROM library_codes WHERE id = :id")
    suspend fun code(id: String): LibraryCodeEntity?

    @Query("DELETE FROM library_codes WHERE id = :id")
    suspend fun deleteCode(id: String)

    @Query("DELETE FROM code_attributes WHERE code_id = :codeId")
    suspend fun deleteAttributesOf(codeId: String)

    @Query("UPDATE code_libraries SET name = :name, name_key = :nameKey WHERE id = :id")
    suspend fun rename(id: String, name: String, nameKey: String): Int

    @Query("DELETE FROM code_libraries WHERE id = :id")
    suspend fun deleteLibrary(id: String)
}

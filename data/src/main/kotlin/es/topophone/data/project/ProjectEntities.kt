package es.topophone.data.project

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Un proyecto (S006). Los enums se guardan por su nombre, nunca por su posición, y las fechas en
 * ms UTC. Con `deleted_at` el proyecto está en la papelera (S006/RF-22).
 */
@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(
            entity = ProjectFolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folder_id"],
            onDelete = ForeignKey.SET_NULL, // S006/RF-21: borrar la carpeta saca sus proyectos
        ),
    ],
    indices = [Index("folder_id"), Index("deleted_at")],
)
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val author: String,
    val description: String,
    val crs: String,
    @ColumnInfo(name = "linear_unit") val linearUnit: String,
    @ColumnInfo(name = "angular_unit") val angularUnit: String,
    @ColumnInfo(name = "corrections_datum") val correctionsDatum: String,
    @ColumnInfo(name = "folder_id") val folderId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "opened_at") val openedAt: Long?,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    /** Altitud o altura elipsoidal (S008/RF-2); los proyectos de la v2 quedan con altitud. */
    @ColumnInfo(name = "height_system", defaultValue = "ORTHOMETRIC") val heightSystem: String = "ORTHOMETRIC",
    /** La superficie activa del proyecto, o NULL si no hay (S042/RF-16); sin clave foránea, borrarla la pone a NULL a mano. */
    @ColumnInfo(name = "active_surface_id") val activeSurfaceId: String? = null,
    /** El desplazamiento vertical sobre la superficie, en metros, positivo hacia arriba (S042/RF-19). */
    @ColumnInfo(name = "vertical_offset_m", defaultValue = "0") val verticalOffsetM: Double = 0.0,
    /** El punto central opcional, latitud y longitud del suelo sin redondear; ambas NULL si no lo hay (S006/RF-32). */
    @ColumnInfo(name = "central_latitude") val centralLatitude: Double? = null,
    @ColumnInfo(name = "central_longitude") val centralLongitude: Double? = null,
    /** El eje activo del proyecto, o NULL si no hay (S049/RF-14); sin clave foránea, borrarlo la pone a NULL a mano. */
    @ColumnInfo(name = "active_alignment_id") val activeAlignmentId: String? = null,
    /** La definición completa del SC si es de la biblioteca de SC (S034/RF-13); NULL en los de España. */
    @ColumnInfo(name = "crs_definition") val crsDefinition: String? = null,
    /** La rejilla del geoide del proyecto con un SC de la biblioteca (S034/RF-14); NULL si no tiene. */
    @ColumnInfo(name = "geoid_grid") val geoidGrid: String? = null,
    /** La ondulación constante del geoide de un SC personalizado, en metros (S035/RF-5); NULL si no tiene. */
    @ColumnInfo(name = "geoid_constant_m") val geoidConstantM: Double? = null,
    /** El plano de terreno (S058/RF-6): la posición del suelo de su origen y el factor; las tres a la vez o ninguna. */
    @ColumnInfo(name = "terrain_origin_lat") val terrainOriginLat: Double? = null,
    @ColumnInfo(name = "terrain_origin_lon") val terrainOriginLon: Double? = null,
    @ColumnInfo(name = "terrain_factor") val terrainFactor: Double? = null,
)

/** Una carpeta de proyectos; un solo nivel (S006/RF-17). */
@Entity(tableName = "project_folders")
data class ProjectFolderEntity(@PrimaryKey val id: String, val name: String)

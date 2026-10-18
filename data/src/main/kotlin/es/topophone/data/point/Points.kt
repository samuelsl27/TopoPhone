package es.topophone.data.point

import android.database.sqlite.SQLiteConstraintException
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import es.topophone.core.model.point.CollectedPoint
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.ProjectEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Un punto recolectado (S010/RF-16 a RF-18). Las coordenadas se guardan sin redondear; el nombre es único en su
 * proyecto (S010/RF-3) y el punto se borra con el proyecto.
 */
@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id", "name"], unique = true)],
)
data class PointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "antenna_lat") val antennaLat: Double,
    @ColumnInfo(name = "antenna_lon") val antennaLon: Double,
    @ColumnInfo(name = "antenna_h") val antennaH: Double?,
    @ColumnInfo(name = "pole_height") val poleHeight: Double,
    @ColumnInfo(name = "ground_lat") val groundLat: Double,
    @ColumnInfo(name = "ground_lon") val groundLon: Double,
    @ColumnInfo(name = "ground_h") val groundH: Double?,
    val easting: Double?,
    val northing: Double?,
    val height: Double?,
    val solution: String,
    val satellites: Int?,
    val hdop: Double?,
    @ColumnInfo(name = "correction_age") val correctionAge: Double?,
    @ColumnInfo(name = "sigma_h") val sigmaH: Double?,
    @ColumnInfo(name = "sigma_v") val sigmaV: Double?,
    val mode: String,
    @ColumnInfo(name = "epochs_used") val epochsUsed: Int,
    @ColumnInfo(name = "epochs_discarded") val epochsDiscarded: Int,
    @ColumnInfo(name = "from_replay") val fromReplay: Boolean,
    /** Condiciones del control de precisión que no cumple, separadas por comas; vacío si cumple (S010/RF-15). */
    @ColumnInfo(name = "out_of_tolerance") val outOfTolerance: String,
    @ColumnInfo(name = "raw_nmea") val rawNmea: String,
    @ColumnInfo(name = "measured_from") val measuredFrom: Long,
    @ColumnInfo(name = "measured_to") val measuredTo: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface PointDao {
    /** Una inserción: con WAL y `synchronous=FULL`, o queda entera o no queda (RNF-T6). */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(point: PointEntity)

    @Query("SELECT * FROM points WHERE project_id = :projectId ORDER BY created_at")
    fun byProject(projectId: String): Flow<List<PointEntity>>

    @Query("SELECT COUNT(*) FROM points WHERE project_id = :projectId")
    fun count(projectId: String): Flow<Int>

    @Query("SELECT name FROM points WHERE project_id = :projectId ORDER BY created_at DESC LIMIT 1")
    suspend fun lastName(projectId: String): String?

    @Query("SELECT EXISTS(SELECT 1 FROM points WHERE project_id = :projectId AND name = :name)")
    suspend fun nameExists(projectId: String, name: String): Boolean
}

/** Resultado de guardar un punto. */
sealed interface PointSaveResult {
    data object Saved : PointSaveResult

    /** Ya hay un punto con ese nombre en el proyecto (S010/RF-3). */
    data object DuplicateName : PointSaveResult
}

/** Puntos recolectados de cada proyecto (S010). */
interface PointRepository {
    suspend fun create(point: CollectedPoint): PointSaveResult

    fun points(projectId: String): Flow<List<CollectedPoint>>

    fun count(projectId: String): Flow<Int>

    suspend fun lastName(projectId: String): String?

    suspend fun nameExists(projectId: String, name: String): Boolean
}

class RoomPointRepository(database: TopoPhoneDatabase) : PointRepository {
    private val dao = database.points()

    override suspend fun create(point: CollectedPoint): PointSaveResult = try {
        dao.insert(point.toEntity())
        PointSaveResult.Saved
    } catch (_: SQLiteConstraintException) {
        PointSaveResult.DuplicateName // el índice único de (proyecto, nombre)
    }

    override fun points(projectId: String): Flow<List<CollectedPoint>> = dao.byProject(projectId).map { list -> list.map { it.toPoint() } }

    override fun count(projectId: String): Flow<Int> = dao.count(projectId)

    override suspend fun lastName(projectId: String): String? = dao.lastName(projectId)

    override suspend fun nameExists(projectId: String, name: String): Boolean = dao.nameExists(projectId, name)
}

private fun CollectedPoint.toEntity() = PointEntity(
    id = id,
    projectId = projectId,
    name = name,
    description = description,
    antennaLat = antenna.latitudeDeg,
    antennaLon = antenna.longitudeDeg,
    antennaH = antenna.ellipsoidalHeightM,
    poleHeight = poleHeightM,
    groundLat = ground.latitudeDeg,
    groundLon = ground.longitudeDeg,
    groundH = ground.ellipsoidalHeightM,
    easting = easting,
    northing = northing,
    height = height,
    solution = quality.solution,
    satellites = quality.satellites,
    hdop = quality.hdop,
    correctionAge = quality.correctionAgeS,
    sigmaH = quality.horizontalM,
    sigmaV = quality.verticalM,
    mode = quality.mode.name,
    epochsUsed = quality.epochsUsed,
    epochsDiscarded = quality.epochsDiscarded,
    fromReplay = quality.fromReplay,
    outOfTolerance = outOfTolerance.sorted().joinToString(",") { it.name },
    rawNmea = rawNmea,
    measuredFrom = measuredFromMs,
    measuredTo = measuredToMs,
    createdAt = createdAtMs,
)

private fun PointEntity.toPoint() = CollectedPoint(
    id = id,
    projectId = projectId,
    name = name,
    description = description,
    antenna = GeodeticPosition(antennaLat, antennaLon, antennaH),
    poleHeightM = poleHeight,
    ground = GeodeticPosition(groundLat, groundLon, groundH),
    easting = easting,
    northing = northing,
    height = height,
    quality = PointQuality(
        solution = solution,
        satellites = satellites,
        hdop = hdop,
        correctionAgeS = correctionAge,
        horizontalM = sigmaH,
        verticalM = sigmaV,
        mode = MeasurementMode.entries.firstOrNull { it.name == mode } ?: MeasurementMode.INSTANT,
        epochsUsed = epochsUsed,
        epochsDiscarded = epochsDiscarded,
        fromReplay = fromReplay,
    ),
    rawNmea = rawNmea,
    outOfTolerance = outOfTolerance.split(',').mapNotNull { name -> PrecisionFailure.entries.firstOrNull { it.name == name } }.toSet(),
    measuredFromMs = measuredFrom,
    measuredToMs = measuredTo,
    createdAtMs = createdAt,
)

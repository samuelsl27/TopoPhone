package es.topophone.data.point

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointBase
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointHistoryEntry
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PointSnapshot
import es.topophone.core.model.point.PointTilt
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.data.project.ProjectEntity

/**
 * Un punto del proyecto (S010, S011). Las columnas principales (`ground_*`, `easting`, `northing`, `height`) son
 * siempre sus coordenadas actuales; las de la medida van en `measured_*`. Las de la medida bruta (antena, NMEA,
 * calidad) son NULL en los puntos manuales y no se escriben nunca después de crear el punto (RNF-T6).
 *
 * El nombre es único entre los puntos activos de un proyecto (S010/RF-3): `trash_key` vale '' en ellos y el id
 * en los de la papelera, que así dejan libre su nombre (S011/RF-25). El punto se borra con el proyecto.
 */
@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["project_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["project_id", "name", "trash_key"], unique = true)],
)
data class PointEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val name: String,
    val description: String,
    @ColumnInfo(defaultValue = "'MEASURED'") val origin: String,
    @ColumnInfo(name = "antenna_lat") val antennaLat: Double?,
    @ColumnInfo(name = "antenna_lon") val antennaLon: Double?,
    @ColumnInfo(name = "antenna_h") val antennaH: Double?,
    @ColumnInfo(name = "pole_height") val poleHeight: Double?,
    @ColumnInfo(name = "ground_lat") val groundLat: Double,
    @ColumnInfo(name = "ground_lon") val groundLon: Double,
    @ColumnInfo(name = "ground_h") val groundH: Double?,
    val easting: Double?,
    val northing: Double?,
    val height: Double?,
    @ColumnInfo(name = "measured_ground_lat") val measuredGroundLat: Double?,
    @ColumnInfo(name = "measured_ground_lon") val measuredGroundLon: Double?,
    @ColumnInfo(name = "measured_ground_h") val measuredGroundH: Double?,
    @ColumnInfo(name = "measured_easting") val measuredEasting: Double?,
    @ColumnInfo(name = "measured_northing") val measuredNorthing: Double?,
    @ColumnInfo(name = "measured_height") val measuredHeight: Double?,
    val solution: String?,
    val satellites: Int?,
    val hdop: Double?,
    @ColumnInfo(name = "correction_age") val correctionAge: Double?,
    @ColumnInfo(name = "sigma_h") val sigmaH: Double?,
    @ColumnInfo(name = "sigma_v") val sigmaV: Double?,
    val mode: String?,
    @ColumnInfo(name = "epochs_used") val epochsUsed: Int?,
    @ColumnInfo(name = "epochs_discarded") val epochsDiscarded: Int?,
    @ColumnInfo(name = "from_replay") val fromReplay: Boolean?,
    /** Condiciones del control de precisión que no cumple, separadas por comas; vacío si cumple (S010/RF-15). */
    @ColumnInfo(name = "out_of_tolerance") val outOfTolerance: String,
    @ColumnInfo(name = "raw_nmea") val rawNmea: String?,
    @ColumnInfo(name = "measured_from") val measuredFrom: Long?,
    @ColumnInfo(name = "measured_to") val measuredTo: Long?,
    @ColumnInfo(name = "typed_form") val typedForm: String?,
    @ColumnInfo(name = "typed_first") val typedFirst: Double?,
    @ColumnInfo(name = "typed_second") val typedSecond: Double?,
    @ColumnInfo(name = "typed_height") val typedHeight: Double?,
    @ColumnInfo(name = "typed_height_kind") val typedHeightKind: String?,
    @ColumnInfo(defaultValue = "0") val edited: Boolean,
    @ColumnInfo(defaultValue = "0") val hidden: Boolean,
    @ColumnInfo(name = "copy_of") val copyOf: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long?,
    @ColumnInfo(name = "trash_key", defaultValue = "''") val trashKey: String,
    // S015/RF-20: la base con la que se midió; NULL sin base, en los manuales y en los de versiones anteriores.
    @ColumnInfo(name = "base_lat") val baseLat: Double? = null,
    @ColumnInfo(name = "base_lon") val baseLon: Double? = null,
    @ColumnInfo(name = "base_h") val baseH: Double? = null,
    @ColumnInfo(name = "base_source") val baseSource: String? = null,
    @ColumnInfo(name = "base_station") val baseStation: Int? = null,
    @ColumnInfo(name = "base_antenna_height") val baseAntennaHeight: Double? = null,
    val baseline: Double? = null,
    // S016/RF-22: el modo de inclinación y la inclinación, en radianes y metros; NULL en los manuales.
    @ColumnInfo(name = "tilt_mode") val tiltMode: String? = null,
    @ColumnInfo(name = "tilt_verified") val tiltVerified: Boolean? = null,
    val tilt: Double? = null,
    @ColumnInfo(name = "tilt_direction") val tiltDirection: Double? = null,
    @ColumnInfo(name = "tilt_heading") val tiltHeading: Double? = null,
    @ColumnInfo(name = "tilt_max") val tiltMax: Double? = null,
    @ColumnInfo(name = "tilt_sigma_h") val tiltSigmaH: Double? = null,
    @ColumnInfo(name = "tilt_sigma_v") val tiltSigmaV: Double? = null,
    @ColumnInfo(name = "tilt_pole_e") val tiltPoleE: Double? = null,
    @ColumnInfo(name = "tilt_pole_n") val tiltPoleN: Double? = null,
    @ColumnInfo(name = "tilt_pole_u") val tiltPoleU: Double? = null,
    @ColumnInfo(name = "tilt_receiver_pole") val tiltReceiverPole: Double? = null,
    // S017/RF-12, RF-14: el tiempo promediado, las desviaciones típicas y si fue automático; NULL en los manuales y en los de antes.
    @ColumnInfo(name = "avg_time") val avgTime: Double? = null,
    @ColumnInfo(name = "dev_h") val devH: Double? = null,
    @ColumnInfo(name = "dev_v") val devV: Double? = null,
    @ColumnInfo(name = "auto_mode") val autoMode: Boolean? = null,
    // S017/RF-12: los tramos de frases NMEA que van en `point_nmea` por ser demasiadas para `raw_nmea`; 0 si van en ella.
    @ColumnInfo(name = "nmea_chunks", defaultValue = "0") val nmeaChunks: Int = 0,
)

/**
 * Un tramo de las frases NMEA de un punto medido, cuando son demasiadas para la fila del punto (S017/RF-12; plan A6): el
 * cursor de Android lee unos 2 MB por fila y un promediado de 60 min a 10 Hz son unos 12 MB. Se borra con el punto.
 */
@Entity(
    tableName = "point_nmea",
    primaryKeys = ["point_id", "seq"],
    foreignKeys = [
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PointNmeaEntity(@ColumnInfo(name = "point_id") val pointId: String, val seq: Int, val text: String)

/** Una versión anterior de un punto, con la fecha del cambio (S011/RF-10; RNF-T6). Se borra con el punto. */
@Entity(
    tableName = "point_history",
    foreignKeys = [
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["point_id"])],
)
data class PointHistoryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "point_id") val pointId: String,
    @ColumnInfo(name = "changed_at") val changedAt: Long,
    val change: String,
    val name: String,
    val description: String,
    @ColumnInfo(name = "pole_height") val poleHeight: Double?,
    @ColumnInfo(name = "ground_lat") val groundLat: Double,
    @ColumnInfo(name = "ground_lon") val groundLon: Double,
    @ColumnInfo(name = "ground_h") val groundH: Double?,
    val easting: Double?,
    val northing: Double?,
    val height: Double?,
    @ColumnInfo(name = "measured_ground_lat") val measuredGroundLat: Double?,
    @ColumnInfo(name = "measured_ground_lon") val measuredGroundLon: Double?,
    @ColumnInfo(name = "measured_ground_h") val measuredGroundH: Double?,
    @ColumnInfo(name = "measured_easting") val measuredEasting: Double?,
    @ColumnInfo(name = "measured_northing") val measuredNorthing: Double?,
    @ColumnInfo(name = "measured_height") val measuredHeight: Double?,
    @ColumnInfo(name = "typed_form") val typedForm: String?,
    @ColumnInfo(name = "typed_first") val typedFirst: Double?,
    @ColumnInfo(name = "typed_second") val typedSecond: Double?,
    @ColumnInfo(name = "typed_height") val typedHeight: Double?,
    @ColumnInfo(name = "typed_height_kind") val typedHeightKind: String?,
    val edited: Boolean,
)

internal fun Point.toEntity(): PointEntity {
    val m = measurement
    val quality = m?.quality
    return PointEntity(
        id = id,
        projectId = projectId,
        name = name,
        description = description,
        origin = origin.name,
        antennaLat = m?.antenna?.latitudeDeg,
        antennaLon = m?.antenna?.longitudeDeg,
        antennaH = m?.antenna?.ellipsoidalHeightM,
        poleHeight = m?.poleHeightM,
        groundLat = coordinates.ground.latitudeDeg,
        groundLon = coordinates.ground.longitudeDeg,
        groundH = coordinates.ground.ellipsoidalHeightM,
        easting = coordinates.easting,
        northing = coordinates.northing,
        height = coordinates.height,
        measuredGroundLat = m?.coordinates?.ground?.latitudeDeg,
        measuredGroundLon = m?.coordinates?.ground?.longitudeDeg,
        measuredGroundH = m?.coordinates?.ground?.ellipsoidalHeightM,
        measuredEasting = m?.coordinates?.easting,
        measuredNorthing = m?.coordinates?.northing,
        measuredHeight = m?.coordinates?.height,
        solution = quality?.solution,
        satellites = quality?.satellites,
        hdop = quality?.hdop,
        correctionAge = quality?.correctionAgeS,
        sigmaH = quality?.horizontalM,
        sigmaV = quality?.verticalM,
        mode = quality?.mode?.name,
        epochsUsed = quality?.epochsUsed,
        epochsDiscarded = quality?.epochsDiscarded,
        fromReplay = quality?.fromReplay,
        outOfTolerance = m?.outOfTolerance.orEmpty().sorted().joinToString(",") { it.name },
        rawNmea = m?.rawNmea,
        measuredFrom = m?.measuredFromMs,
        measuredTo = m?.measuredToMs,
        typedForm = typed?.form?.name,
        typedFirst = typed?.first,
        typedSecond = typed?.second,
        typedHeight = typed?.height,
        typedHeightKind = typed?.heightKind?.name,
        edited = edited,
        hidden = hidden,
        copyOf = copyOf,
        createdAt = createdAtMs,
        deletedAt = deletedAtMs,
        trashKey = if (deletedAtMs == null) "" else id,
        baseLat = m?.base?.position?.latitudeDeg,
        baseLon = m?.base?.position?.longitudeDeg,
        baseH = m?.base?.position?.ellipsoidalHeightM,
        baseSource = m?.base?.source?.name,
        baseStation = m?.base?.stationId,
        baseAntennaHeight = m?.base?.antennaHeightM,
        baseline = m?.base?.baselineM,
        tiltMode = m?.tilt?.mode?.name,
        tiltVerified = m?.tilt?.verified,
        tilt = m?.tilt?.angles?.tiltRad,
        tiltDirection = m?.tilt?.angles?.directionRad,
        tiltHeading = m?.tilt?.angles?.headingRad,
        tiltMax = m?.tilt?.angles?.maxTiltRad,
        tiltSigmaH = m?.tilt?.angles?.horizontalAccuracyM,
        tiltSigmaV = m?.tilt?.angles?.verticalAccuracyM,
        tiltPoleE = m?.tilt?.angles?.pole?.east,
        tiltPoleN = m?.tilt?.angles?.pole?.north,
        tiltPoleU = m?.tilt?.angles?.pole?.up,
        tiltReceiverPole = m?.tilt?.angles?.receiverPoleHeightM,
        avgTime = quality?.averagedTimeS,
        devH = quality?.deviationHorizontalM,
        devV = quality?.deviationVerticalM,
        autoMode = quality?.auto,
        nmeaChunks = m?.nmeaChunks ?: 0,
    )
}

internal fun PointEntity.toPoint(): Point = Point(
    id = id,
    projectId = projectId,
    name = name,
    description = description,
    origin = enumOr(origin, PointOrigin.MEASURED),
    coordinates = PointCoordinates(GeodeticPosition(groundLat, groundLon, groundH), easting, northing, height),
    measurement = measurement(),
    typed = typed(typedForm, typedFirst, typedSecond, typedHeight, typedHeightKind),
    edited = edited,
    hidden = hidden,
    copyOf = copyOf,
    createdAtMs = createdAt,
    deletedAtMs = deletedAt,
)

/** La medida de un punto medido; null en uno manual (sin antena ni NMEA). */
private fun PointEntity.measurement(): Measurement? {
    if (origin != PointOrigin.MEASURED.name) return null
    val antennaLatitude = antennaLat ?: return null
    val antennaLongitude = antennaLon ?: return null
    return Measurement(
        antenna = GeodeticPosition(antennaLatitude, antennaLongitude, antennaH),
        poleHeightM = poleHeight ?: return null,
        quality = PointQuality(
            solution = solution ?: return null,
            satellites = satellites,
            hdop = hdop,
            correctionAgeS = correctionAge,
            horizontalM = sigmaH,
            verticalM = sigmaV,
            mode = enumOr(mode, MeasurementMode.INSTANT),
            epochsUsed = epochsUsed ?: 0,
            epochsDiscarded = epochsDiscarded ?: 0,
            fromReplay = fromReplay ?: false,
            averagedTimeS = avgTime,
            deviationHorizontalM = devH,
            deviationVerticalM = devV,
            auto = autoMode,
        ),
        rawNmea = rawNmea.orEmpty(),
        outOfTolerance = outOfTolerance.split(',').mapNotNull { name -> PrecisionFailure.entries.firstOrNull { it.name == name } }.toSet(),
        measuredFromMs = measuredFrom ?: createdAt,
        measuredToMs = measuredTo ?: createdAt,
        coordinates = coordinates(measuredGroundLat, measuredGroundLon, measuredGroundH, measuredEasting, measuredNorthing, measuredHeight)
            ?: PointCoordinates(GeodeticPosition(groundLat, groundLon, groundH), easting, northing, height),
        base = base(),
        tilt = tilt(),
        nmeaChunks = nmeaChunks,
    )
}

/** El modo de inclinación y la inclinación de la medida; null en los puntos sin modo (S016/RF-22). */
private fun PointEntity.tilt(): PointTilt? {
    val mode = TiltMode.entries.firstOrNull { it.name == tiltMode } ?: return null
    return PointTilt(mode, verified = tiltVerified ?: false, angles = angles())
}

private fun PointEntity.angles(): TiltAngles? = TiltAngles(
    tiltRad = tilt ?: return null,
    directionRad = tiltDirection ?: return null,
    headingRad = tiltHeading,
    maxTiltRad = tiltMax ?: return null,
    horizontalAccuracyM = tiltSigmaH ?: return null,
    verticalAccuracyM = tiltSigmaV ?: return null,
    pole = PoleVector(tiltPoleE ?: return null, tiltPoleN ?: return null, tiltPoleU ?: return null),
    receiverPoleHeightM = tiltReceiverPole,
)

/** La base de la medida; null si no se guardó (S015/RF-20). */
private fun PointEntity.base(): PointBase? {
    val latitude = baseLat ?: return null
    val longitude = baseLon ?: return null
    return PointBase(
        position = GeodeticPosition(latitude, longitude, baseH),
        source = enumOr(baseSource, BaseSource.RECEIVER),
        stationId = baseStation,
        antennaHeightM = baseAntennaHeight,
        baselineM = baseline ?: return null,
    )
}

internal fun PointSnapshot.toHistoryEntity(id: String, pointId: String, changedAtMs: Long, change: PointChange) = PointHistoryEntity(
    id = id,
    pointId = pointId,
    changedAt = changedAtMs,
    change = change.name,
    name = name,
    description = description,
    poleHeight = poleHeightM,
    groundLat = coordinates.ground.latitudeDeg,
    groundLon = coordinates.ground.longitudeDeg,
    groundH = coordinates.ground.ellipsoidalHeightM,
    easting = coordinates.easting,
    northing = coordinates.northing,
    height = coordinates.height,
    measuredGroundLat = measuredCoordinates?.ground?.latitudeDeg,
    measuredGroundLon = measuredCoordinates?.ground?.longitudeDeg,
    measuredGroundH = measuredCoordinates?.ground?.ellipsoidalHeightM,
    measuredEasting = measuredCoordinates?.easting,
    measuredNorthing = measuredCoordinates?.northing,
    measuredHeight = measuredCoordinates?.height,
    typedForm = typed?.form?.name,
    typedFirst = typed?.first,
    typedSecond = typed?.second,
    typedHeight = typed?.height,
    typedHeightKind = typed?.heightKind?.name,
    edited = edited,
)

internal fun PointHistoryEntity.toEntry() = PointHistoryEntry(
    id = id,
    pointId = pointId,
    changedAtMs = changedAt,
    change = enumOr(change, PointChange.EDIT),
    previous = PointSnapshot(
        name = name,
        description = description,
        poleHeightM = poleHeight,
        coordinates = PointCoordinates(GeodeticPosition(groundLat, groundLon, groundH), easting, northing, height),
        measuredCoordinates = coordinates(
            measuredGroundLat,
            measuredGroundLon,
            measuredGroundH,
            measuredEasting,
            measuredNorthing,
            measuredHeight,
        ),
        typed = typed(typedForm, typedFirst, typedSecond, typedHeight, typedHeightKind),
        edited = edited,
    ),
)

private fun coordinates(lat: Double?, lon: Double?, h: Double?, easting: Double?, northing: Double?, height: Double?): PointCoordinates? {
    if (lat == null || lon == null) return null
    return PointCoordinates(GeodeticPosition(lat, lon, h), easting, northing, height)
}

private fun typed(form: String?, first: Double?, second: Double?, height: Double?, kind: String?): TypedCoordinates? {
    if (form == null || first == null || second == null) return null
    return TypedCoordinates(enumOr(form, EntryForm.PROJECT_CRS), first, second, height, enumOr(kind, HeightKind.PROJECT))
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback

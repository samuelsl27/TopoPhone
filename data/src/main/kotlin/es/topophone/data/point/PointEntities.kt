package es.topophone.data.point

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import es.topophone.core.formats.cogo.CogoRefJson
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.alignment.slope.SlopeSide
import es.topophone.core.model.alignment.slope.SlopeStakeInfo
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.codes.AttributeType
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointAttribute
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.point.AdmittedSolutions
import es.topophone.core.model.point.AppliedShift
import es.topophone.core.model.point.AxisHeightRef
import es.topophone.core.model.point.AxisRef
import es.topophone.core.model.point.BaseShiftVector
import es.topophone.core.model.point.BaseSource
import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.GearUsed
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.LineStakeoutMode
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
import es.topophone.core.model.point.PoleKind
import es.topophone.core.model.point.PoleVector
import es.topophone.core.model.point.PositionSource
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.point.StakeSource
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.point.TiltAngles
import es.topophone.core.model.point.TiltMode
import es.topophone.core.model.point.TypedCoordinates
import es.topophone.core.model.point.hasMeasurement
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
    indices = [Index(value = ["project_id", "name", "trash_key"], unique = true), Index(value = ["import_id"])],
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
    // S018/RF-28: de qué punto de diseño viene un punto replanteado, por valor, y lo que se desvió (medido − diseño); NULL en los demás.
    @ColumnInfo(name = "stake_design_id") val stakeDesignId: String? = null,
    @ColumnInfo(name = "stake_design_name") val stakeDesignName: String? = null,
    @ColumnInfo(name = "stake_design_east") val stakeDesignEast: Double? = null,
    @ColumnInfo(name = "stake_design_north") val stakeDesignNorth: Double? = null,
    @ColumnInfo(name = "stake_design_height") val stakeDesignHeight: Double? = null,
    @ColumnInfo(name = "stake_design_lat") val stakeDesignLat: Double? = null,
    @ColumnInfo(name = "stake_design_lon") val stakeDesignLon: Double? = null,
    @ColumnInfo(name = "stake_design_ground_h") val stakeDesignGroundH: Double? = null,
    @ColumnInfo(name = "stake_d_east") val stakeDEast: Double? = null,
    @ColumnInfo(name = "stake_d_north") val stakeDNorth: Double? = null,
    @ColumnInfo(name = "stake_d_horizontal") val stakeDHorizontal: Double? = null,
    @ColumnInfo(name = "stake_d_height") val stakeDHeight: Double? = null,
    // S019/RF-13, RF-14: la cota de diseño usada, las tolerancias vigentes al medir y si cumple; NULL en los replanteos de antes.
    @ColumnInfo(name = "stake_design_elevation") val stakeDesignElevation: Double? = null,
    @ColumnInfo(name = "stake_tol_h") val stakeTolH: Double? = null,
    @ColumnInfo(name = "stake_tol_v") val stakeTolV: Double? = null,
    @ColumnInfo(name = "stake_within") val stakeWithin: Boolean? = null,
    // S041/RF-28: si el punto de diseño era un elemento de un dibujo, sus nombres (copiados: el dibujo puede borrarse), el modo de
    // línea, el PK, el desplazamiento lateral y el error lateral al medir; NULL en los demás.
    @ColumnInfo(name = "stake_cad_drawing") val stakeCadDrawing: String? = null,
    @ColumnInfo(name = "stake_cad_layer") val stakeCadLayer: String? = null,
    @ColumnInfo(name = "stake_cad_kind") val stakeCadKind: String? = null,
    @ColumnInfo(name = "stake_mode") val stakeMode: String? = null,
    @ColumnInfo(name = "stake_chainage") val stakeChainage: Double? = null,
    @ColumnInfo(name = "stake_offset") val stakeOffset: Double? = null,
    @ColumnInfo(name = "stake_d_lateral") val stakeDLateral: Double? = null,
    // S042/RF-28, RF-29: contra qué cota se replanteó y, con la superficie, su nombre y la fecha de su importación (copiados: la
    // superficie puede borrarse), su cota en la punta y el desplazamiento vertical; NULL en los demás.
    @ColumnInfo(name = "stake_elev_source") val stakeElevSource: String? = null,
    @ColumnInfo(name = "stake_surface_name") val stakeSurfaceName: String? = null,
    @ColumnInfo(name = "stake_surface_imported_ms") val stakeSurfaceImportedMs: Long? = null,
    @ColumnInfo(name = "stake_surface_elev") val stakeSurfaceElev: Double? = null,
    @ColumnInfo(name = "stake_vertical_offset") val stakeVerticalOffset: Double? = null,
    // S028/RF-29: la importación de la que viene un punto importado, para deshacerla; NULL en los demás.
    @ColumnInfo(name = "import_id") val importId: String? = null,
    // S046/RF-24: el jalón y la antena con los que se midió, por valor (copiados: pueden editarse o borrarse); NULL en los demás.
    @ColumnInfo(name = "pole_name") val poleName: String? = null,
    @ColumnInfo(name = "pole_kind") val poleKind: String? = null,
    @ColumnInfo(name = "pole_entered_m") val poleEnteredM: Double? = null,
    @ColumnInfo(name = "antenna_profile") val antennaProfile: String? = null,
    @ColumnInfo(name = "antenna_offset_m") val antennaOffsetM: Double? = null,
    @ColumnInfo(name = "antenna_height_m") val antennaHeightM: Double? = null,
    // S047/RF-15: los tipos de solución admitidos al medir (`FIX,FLOAT`) y las épocas descartadas por tipo; NULL en los demás.
    @ColumnInfo(name = "admitted_solutions") val admittedSolutions: String? = null,
    @ColumnInfo(name = "epochs_discarded_by_type") val epochsDiscardedByType: Int? = null,
    // S049/RF-22: el eje (por nombre, copiado: puede borrarse), el PK y el desplazamiento de un punto calculado sobre un eje; NULL en los demás.
    @ColumnInfo(name = "axis_name") val axisName: String? = null,
    @ColumnInfo(name = "axis_chainage") val axisChainage: Double? = null,
    @ColumnInfo(name = "axis_offset") val axisOffset: Double? = null,
    // S054/RF-10: si el punto de diseño era un PK de un eje, su nombre (copiado: el eje puede borrarse), el espesor y la diferencia de PK;
    // y en una medida de "Analizar", la diferencia de cota y contra qué se calculó (RF-13); NULL en los demás.
    @ColumnInfo(name = "stake_axis") val stakeAxis: String? = null,
    @ColumnInfo(name = "stake_thickness") val stakeThickness: Double? = null,
    @ColumnInfo(name = "stake_d_chainage") val stakeDChainage: Double? = null,
    @ColumnInfo(name = "axis_d_height") val axisDHeight: Double? = null,
    @ColumnInfo(name = "axis_height_ref") val axisHeightRef: String? = null,
    // S020/RF-8: el desplazamiento de base con que se midió (el cálculo, su nombre y su vector) y la punta sin desplazar; las
    // coordenadas del punto ya lo llevan. NULL en los demás.
    @ColumnInfo(name = "shift_id") val shiftId: String? = null,
    @ColumnInfo(name = "shift_name") val shiftName: String? = null,
    @ColumnInfo(name = "shift_dx") val shiftDx: Double? = null,
    @ColumnInfo(name = "shift_dy") val shiftDy: Double? = null,
    @ColumnInfo(name = "shift_dz") val shiftDz: Double? = null,
    @ColumnInfo(name = "unshifted_ground_lat") val unshiftedGroundLat: Double? = null,
    @ColumnInfo(name = "unshifted_ground_lon") val unshiftedGroundLon: Double? = null,
    @ColumnInfo(name = "unshifted_ground_h") val unshiftedGroundH: Double? = null,
    // S050/RF-15, RF-19: el código de sección de un punto calculado sobre un eje o replanteado por código (copiado); NULL en los demás.
    @ColumnInfo(name = "section_code") val sectionCode: String? = null,
    // S051/RF-15: lo propio del talud de un punto replanteado (cabeza o pie); NULL en los demás.
    @ColumnInfo(name = "slope_kind") val slopeKind: String? = null,
    @ColumnInfo(name = "slope_side") val slopeSide: String? = null,
    @ColumnInfo(name = "slope_ratio") val slopeRatio: Double? = null,
    @ColumnInfo(name = "slope_height") val slopeHeight: Double? = null,
    @ColumnInfo(name = "slope_d_height") val slopeDHeight: Double? = null,
    @ColumnInfo(name = "slope_stake_offset") val slopeStakeOffset: Double? = null,
    // S027/RF-12: si el punto de diseño era un objetivo de una línea del proyecto, su identificador y su nombre (copiados, sin clave
    // foránea: la línea puede borrarse y el punto los conserva, RF-16); NULL en los demás.
    @ColumnInfo(name = "stake_line_id") val stakeLineId: String? = null,
    @ColumnInfo(name = "stake_line_name") val stakeLineName: String? = null,
    /** El codigo con que se guardo el punto, por valor (S024/RF-10, plan D3); NULL si no lleva. [codeColor] es 0xRRGGBB. */
    val code: String? = null,
    @ColumnInfo(name = "code_description") val codeDescription: String? = null,
    @ColumnInfo(name = "code_color") val codeColor: Int? = null,
    @ColumnInfo(name = "code_symbol") val codeSymbol: String? = null,
    /** El rastro del calculo de un punto de origen COGO, en JSON (S038/RF-22, plan D10); NULL en los demas. */
    val cogo: String? = null,
    /** De dónde vino la posición (S022/RF-11, plan D2): `RECEIVER`, `REPLAY`, `PHONE`, `PHONE_MOCK` o `VIRTUAL`; NULL en los de antes. */
    @ColumnInfo(name = "position_source") val positionSource: String? = null,
    /** El modelo, el número de serie y el firmware del receptor con que se midió (S043/RF-19, plan D7); NULL en los de antes. */
    @ColumnInfo(name = "equipment_model") val equipmentModel: String? = null,
    @ColumnInfo(name = "equipment_serial") val equipmentSerial: String? = null,
    @ColumnInfo(name = "equipment_firmware") val equipmentFirmware: String? = null,
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
    /** El codigo que tenia el punto antes del cambio (S024/RF-14, plan D4); NULL si no llevaba. */
    val code: String? = null,
    @ColumnInfo(name = "code_description") val codeDescription: String? = null,
    @ColumnInfo(name = "code_color") val codeColor: Int? = null,
    @ColumnInfo(name = "code_symbol") val codeSymbol: String? = null,
)

/** El valor de un atributo del codigo de un punto, con su nombre y su tipo, en texto canonico (S024/RF-10, plan D3). */
@Entity(
    tableName = "point_attributes",
    primaryKeys = ["point_id", "ordinal"],
    foreignKeys = [
        ForeignKey(entity = PointEntity::class, parentColumns = ["id"], childColumns = ["point_id"], onDelete = ForeignKey.CASCADE),
    ],
)
data class PointAttributeEntity(
    @ColumnInfo(name = "point_id") val pointId: String,
    val ordinal: Int,
    val name: String,
    val type: String,
    val value: String,
)

/** Los valores de los atributos que tenia un punto en una version del historial (S024/RF-14, plan D4). */
@Entity(
    tableName = "point_history_attributes",
    primaryKeys = ["history_id", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = PointHistoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["history_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PointHistoryAttributeEntity(
    @ColumnInfo(name = "history_id") val historyId: String,
    val ordinal: Int,
    val name: String,
    val type: String,
    val value: String,
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
        positionSource = quality?.source?.name,
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
        admittedSolutions = quality?.admitted?.encode(),
        epochsDiscardedByType = quality?.epochsDiscardedByType,
        nmeaChunks = m?.nmeaChunks ?: 0,
        stakeDesignId = staked?.designId,
        stakeDesignName = staked?.designName,
        stakeDesignEast = staked?.design?.easting,
        stakeDesignNorth = staked?.design?.northing,
        stakeDesignHeight = staked?.design?.height,
        stakeDesignLat = staked?.design?.ground?.latitudeDeg,
        stakeDesignLon = staked?.design?.ground?.longitudeDeg,
        stakeDesignGroundH = staked?.design?.ground?.ellipsoidalHeightM,
        stakeDEast = staked?.dEastM,
        stakeDNorth = staked?.dNorthM,
        stakeDHorizontal = staked?.dHorizontalM,
        stakeDHeight = staked?.dHeightM,
        stakeDesignElevation = staked?.designElevationM,
        stakeTolH = staked?.toleranceHM,
        stakeTolV = staked?.toleranceVM,
        stakeWithin = staked?.withinTolerance,
        stakeCadDrawing = (staked?.source as? StakeSource.CadElement)?.drawingName,
        stakeCadLayer = (staked?.source as? StakeSource.CadElement)?.layerName,
        stakeCadKind = (staked?.source as? StakeSource.CadElement)?.kind?.name,
        stakeMode = staked?.mode?.name,
        stakeChainage = staked?.chainageM,
        stakeOffset = staked?.lateralOffsetM,
        stakeDLateral = staked?.dLateralM,
        stakeAxis = (staked?.source as? StakeSource.Alignment)?.alignmentName,
        stakeLineId = (staked?.source as? StakeSource.ProjectLine)?.lineId,
        stakeLineName = (staked?.source as? StakeSource.ProjectLine)?.lineName,
        stakeThickness = staked?.thicknessM,
        stakeDChainage = staked?.dChainageM,
        stakeElevSource = staked?.elevationSource?.name,
        stakeSurfaceName = staked?.surfaceName,
        stakeSurfaceImportedMs = staked?.surfaceImportedAtMs,
        stakeSurfaceElev = staked?.surfaceElevationM,
        stakeVerticalOffset = staked?.verticalOffsetM,
        importId = importId,
        poleName = m?.gear?.poleName,
        poleKind = m?.gear?.poleKind?.name,
        poleEnteredM = m?.gear?.poleEnteredM,
        antennaProfile = m?.gear?.antennaProfile,
        antennaOffsetM = m?.gear?.antennaOffsetM,
        antennaHeightM = m?.gear?.antennaHeightM,
        axisName = onAxis?.alignmentName,
        axisChainage = onAxis?.chainageM,
        axisOffset = onAxis?.offsetM,
        axisDHeight = onAxis?.dHeightM,
        axisHeightRef = onAxis?.heightRef?.name,
        sectionCode = onAxis?.sectionCode ?: staked?.sectionCode,
        slopeKind = staked?.slope?.kind?.name,
        slopeSide = staked?.slope?.side?.name,
        slopeRatio = staked?.slope?.ratio,
        slopeHeight = staked?.slope?.heightM,
        slopeDHeight = staked?.slope?.dHeightM,
        slopeStakeOffset = staked?.slope?.stakeOffsetM,
        shiftId = m?.shift?.shiftId,
        shiftName = m?.shift?.knownName,
        shiftDx = m?.shift?.vector?.dx,
        shiftDy = m?.shift?.vector?.dy,
        shiftDz = m?.shift?.vector?.dz,
        unshiftedGroundLat = m?.shift?.unshiftedGround?.latitudeDeg,
        unshiftedGroundLon = m?.shift?.unshiftedGround?.longitudeDeg,
        unshiftedGroundH = m?.shift?.unshiftedGround?.ellipsoidalHeightM,
        code = code?.code,
        codeDescription = code?.description,
        codeColor = code?.color,
        codeSymbol = code?.symbol?.name,
        cogo = cogo?.let(CogoRefJson::encode),
    )
}

/** Los valores de los atributos del codigo del punto, en su orden (S024/RF-10). */
internal fun Point.attributeEntities(): List<PointAttributeEntity> =
    code?.attributes.orEmpty().mapIndexed { i, a -> PointAttributeEntity(id, i, a.name, a.type.name, a.value) }

internal fun PointEntity.toPoint(attributes: List<PointAttributeEntity> = emptyList()): Point = Point(
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
    staked = staked(),
    importId = importId,
    onAxis = axisName?.let { name ->
        AxisRef(
            name,
            axisChainage ?: 0.0,
            axisOffset ?: 0.0,
            axisDHeight,
            axisHeightRef?.let { ref -> AxisHeightRef.entries.firstOrNull { it.name == ref } },
            sectionCode,
        )
    },
    code = pointCode(code, codeDescription, codeColor, codeSymbol, attributes.sortedBy { it.ordinal }.map { it.toAttribute() }),
    cogo = cogo?.let(CogoRefJson::decode),
)

private fun pointCode(code: String?, description: String?, color: Int?, symbol: String?, attributes: List<PointAttribute>): PointCode? {
    if (code == null) return null
    return PointCode(code, description.orEmpty(), color ?: 0, enumOr(symbol, CodeSymbol.CIRCLE), attributes)
}

private fun PointAttributeEntity.toAttribute() = PointAttribute(name, enumOr(type, AttributeType.TEXT), value)

private fun PointHistoryAttributeEntity.toAttribute() = PointAttribute(name, enumOr(type, AttributeType.TEXT), value)

/** De qué punto de diseño viene un punto replanteado; null en los demás (S018/RF-28). */
private fun PointEntity.staked(): StakeoutRef? = StakeoutRef(
    designId = stakeDesignId ?: return null,
    designName = stakeDesignName ?: return null,
    design = PointCoordinates(
        GeodeticPosition(stakeDesignLat ?: return null, stakeDesignLon ?: return null, stakeDesignGroundH),
        stakeDesignEast,
        stakeDesignNorth,
        stakeDesignHeight,
    ),
    dEastM = stakeDEast ?: return null,
    dNorthM = stakeDNorth ?: return null,
    dHorizontalM = stakeDHorizontal ?: return null,
    dHeightM = stakeDHeight,
    // Un replanteo de S018 no guardaba la cota usada: era la altura del punto de diseño (S019/RF-26).
    designElevationM = stakeDesignElevation ?: stakeDesignHeight.takeIf { stakeDHeight != null },
    toleranceHM = stakeTolH,
    toleranceVM = stakeTolV,
    withinTolerance = stakeWithin,
    source = cadSource(),
    mode = stakeMode?.let { name -> LineStakeoutMode.entries.firstOrNull { it.name == name } },
    chainageM = stakeChainage,
    lateralOffsetM = stakeOffset,
    dLateralM = stakeDLateral,
    elevationSource = stakeElevSource?.let { name -> ElevationSource.entries.firstOrNull { it.name == name } },
    surfaceName = stakeSurfaceName,
    surfaceImportedAtMs = stakeSurfaceImportedMs,
    surfaceElevationM = stakeSurfaceElev,
    verticalOffsetM = stakeVerticalOffset,
    dChainageM = stakeDChainage,
    thicknessM = stakeThickness,
    sectionCode = sectionCode.takeIf { axisName == null },
    slope = slopeInfo(),
)

/** Lo propio del talud de un punto replanteado; null en los demás (S051/RF-15). */
private fun PointEntity.slopeInfo(): SlopeStakeInfo? {
    val kind = SlopeKind.entries.firstOrNull { it.name == slopeKind } ?: return null
    val side = SlopeSide.entries.firstOrNull { it.name == slopeSide } ?: return null
    return SlopeStakeInfo(kind, side, slopeRatio ?: return null, slopeHeight ?: return null, slopeDHeight, slopeStakeOffset ?: 0.0)
}

/** El dibujo, la capa y el tipo del elemento replanteado; el punto del proyecto si no vino de un dibujo (S041/RF-28). */
private fun PointEntity.cadSource(): StakeSource {
    stakeLineId?.let { return StakeSource.ProjectLine(it, stakeLineName.orEmpty()) } // S027/RF-12
    stakeAxis?.let { return StakeSource.Alignment(it) } // S054/RF-10
    val drawing = stakeCadDrawing ?: return StakeSource.ProjectPoint
    val kind = CadEntityKind.entries.firstOrNull { it.name == stakeCadKind } ?: return StakeSource.ProjectPoint
    return StakeSource.CadElement(drawing, stakeCadLayer.orEmpty(), kind)
}

/** La medida de un punto medido o replanteado; null en uno manual (sin antena ni NMEA). */
private fun PointEntity.measurement(): Measurement? {
    if (!enumOr(origin, PointOrigin.MEASURED).hasMeasurement) return null
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
            source = PositionSource.fromStored(positionSource, fromReplay),
            averagedTimeS = avgTime,
            deviationHorizontalM = devH,
            deviationVerticalM = devV,
            auto = autoMode,
            admitted = AdmittedSolutions.decode(admittedSolutions),
            epochsDiscardedByType = epochsDiscardedByType,
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
        gear = gear(),
        shift = shift(),
    )
}

/** El desplazamiento de base con que se midió; null en los puntos sin él (S020/RF-8). */
private fun PointEntity.shift(): AppliedShift? = AppliedShift(
    shiftId = shiftId ?: return null,
    knownName = shiftName.orEmpty(),
    vector = BaseShiftVector(shiftDx ?: return null, shiftDy ?: return null, shiftDz ?: return null),
    unshiftedGround = GeodeticPosition(unshiftedGroundLat ?: return null, unshiftedGroundLon ?: return null, unshiftedGroundH),
)

/** El jalón y la antena con los que se midió; null en los puntos de antes de S046 (S046/RF-24, RF-29). */
private fun PointEntity.gear(): GearUsed? = GearUsed(
    poleName = poleName ?: return null,
    poleKind = enumOr(poleKind, PoleKind.VERTICAL),
    poleEnteredM = poleEnteredM ?: return null,
    antennaProfile = antennaProfile ?: return null,
    antennaOffsetM = antennaOffsetM ?: return null,
    antennaHeightM = antennaHeightM ?: return null,
)

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
    code = code?.code,
    codeDescription = code?.description,
    codeColor = code?.color,
    codeSymbol = code?.symbol?.name,
)

/** Los valores de los atributos de esta version anterior (S024/RF-14). */
internal fun PointSnapshot.historyAttributeEntities(historyId: String): List<PointHistoryAttributeEntity> =
    code?.attributes.orEmpty().mapIndexed { i, a -> PointHistoryAttributeEntity(historyId, i, a.name, a.type.name, a.value) }

internal fun PointHistoryEntity.toEntry(attributes: List<PointHistoryAttributeEntity> = emptyList()) = PointHistoryEntry(
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
        code = pointCode(code, codeDescription, codeColor, codeSymbol, attributes.sortedBy { it.ordinal }.map { it.toAttribute() }),
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

package es.topophone.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import es.topophone.data.alignment.AlignmentDao
import es.topophone.data.alignment.AlignmentEntity
import es.topophone.data.baseshift.BaseShiftDao
import es.topophone.data.baseshift.BaseShiftEntity
import es.topophone.data.cad.CadChunkAttributesEntity
import es.topophone.data.cad.CadChunkEntity
import es.topophone.data.cad.CadDao
import es.topophone.data.cad.CadDrawingEntity
import es.topophone.data.cad.CadLayerEntity
import es.topophone.data.codes.AttributeOptionEntity
import es.topophone.data.codes.CodeAttributeEntity
import es.topophone.data.codes.CodeLibraryDao
import es.topophone.data.codes.CodeLibraryEntity
import es.topophone.data.codes.LibraryCodeEntity
import es.topophone.data.gear.AntennaProfileEntity
import es.topophone.data.gear.GearDao
import es.topophone.data.gear.ReceiverGearEntity
import es.topophone.data.gear.SavedPoleEntity
import es.topophone.data.line.LineAttributeEntity
import es.topophone.data.line.LineHistoryAttributeEntity
import es.topophone.data.line.LineHistoryEntity
import es.topophone.data.line.LineHistoryVertexEntity
import es.topophone.data.line.LineVertexEntity
import es.topophone.data.line.ProjectLineEntity
import es.topophone.data.linear.LinearSurveyDao
import es.topophone.data.linear.LinearSurveyEntity
import es.topophone.data.linear.LinearSurveyPointEntity
import es.topophone.data.map.MapViewportDao
import es.topophone.data.map.MapViewportEntity
import es.topophone.data.ntrip.NtripProfileDao
import es.topophone.data.ntrip.NtripProfileEntity
import es.topophone.data.photo.PhotoDao
import es.topophone.data.photo.PhotoEventEntity
import es.topophone.data.photo.PointPhotoEntity
import es.topophone.data.point.PointAttributeEntity
import es.topophone.data.point.PointDao
import es.topophone.data.point.PointEntity
import es.topophone.data.point.PointHistoryAttributeEntity
import es.topophone.data.point.PointHistoryEntity
import es.topophone.data.point.PointNmeaEntity
import es.topophone.data.project.ProjectDao
import es.topophone.data.project.ProjectEntity
import es.topophone.data.project.ProjectFolderEntity
import es.topophone.data.section.SectionDao
import es.topophone.data.section.SectionProfileEntity
import es.topophone.data.section.SectionTemplateEntity
import es.topophone.data.section.SuperelevationRowEntity
import es.topophone.data.slope.SlopeDao
import es.topophone.data.slope.SlopeRuleEntity
import es.topophone.data.surface.RasterTileEntity
import es.topophone.data.surface.SurfaceDao
import es.topophone.data.surface.SurfaceEntity
import es.topophone.data.surface.SurfaceTileEntity

/**
 * Base de datos de la app (ADR-8). El esquema crece spec a spec: cada versión nueva lleva su
 * migración y su test contra la anterior, con los esquemas exportados en `data/schemas/` (RNF-T6).
 *
 * - v1 (S006): proyectos y carpetas.
 * - v2 (S007): perfiles NTRIP. Solo añade una tabla, así que la migración la genera Room.
 * - v3 (S008): altura de cada proyecto (`height_system`, por defecto altitud). Migración de Room.
 * - v4 (S009): último encuadre del mapa de cada proyecto. Solo añade una tabla: migración de Room.
 * - v5 (S010): puntos recolectados. Solo añade una tabla: migración de Room.
 * - v6 (S011): puntos manuales (la medida bruta pasa a admitir NULL), coordenadas de la medida aparte, lo escrito,
 *   editado, oculto, copia de y papelera con `trash_key` en el índice del nombre; historial de cada punto. Room rehace
 *   la tabla y [PointsV6] copia la medida de los puntos de S010.
 * - v7 (S015): la base con la que se midió cada punto. Solo añade columnas que admiten NULL: migración de Room.
 * - v8 (S016): el modo de inclinación y la inclinación de cada punto. Columnas que admiten NULL: migración de Room, y
 *   [PointsV8] marca los puntos medidos antes como "Jalón vertical", que es como se calcularon.
 * - v9 (S017): el tiempo promediado, las desviaciones típicas y si fue automático de cada punto (columnas que admiten NULL, y
 *   `nmea_chunks` con 0 por defecto) y la tabla `point_nmea` con los tramos de frases NMEA de los promediados largos. Solo añade
 *   columnas y una tabla: migración de Room.
 * - v10 (S018): el punto de diseño y las diferencias de cada replanteo. Columnas que admiten NULL: migración de Room.
 * - v11 (S019): la cota de diseño usada, las tolerancias y si cumple cada replanteo. Columnas que admiten NULL: migración de Room.
 * - v12 (S028): `import_id` de los puntos importados, con su índice. Columna que admite NULL: migración de Room.
 * - v13 (S040): dibujos importados de un DXF (`cad_drawings`, `cad_layers` y `cad_chunks`). Solo añade tablas: migración de Room.
 * - v14 (S041): de qué elemento de un dibujo viene cada replanteo (columnas que admiten NULL). Migración de Room.
 * - v15 (S042): superficies LandXML (`surfaces` y `surface_tiles`), la superficie activa y el desplazamiento vertical de cada proyecto, y contra
 *   qué cota se replanteó cada punto. Solo añade tablas y columnas con valor por defecto o NULL: migración de Room.
 * - v16 (S046): perfiles de antena propios, jalones guardados y el equipo de cada receptor (`antenna_profiles`, `saved_poles` y
 *   `receiver_gear`), y el jalón y la antena de cada punto (columnas que admiten NULL). Migración de Room.
 * - v17 (S047): los tipos de solución admitidos al medir y las épocas descartadas por tipo de cada punto (columnas que admiten NULL).
 *   Migración de Room.
 * - v18 (S006): el punto central opcional de cada proyecto (`central_latitude` y `central_longitude`, que admiten NULL). Migración de Room.
 * - v19 (S048): de qué fichero viene cada dibujo (`cad_drawings.origin`, `DXF` por defecto), la tabla y el campo de nombre de cada capa
 *   (`cad_layers.source_table` y `name_field`, que admiten NULL) y los atributos de cada trozo (`cad_chunk_attributes`). Migración de Room.
 * - v20 (S049): ejes y rasantes (`alignments`), el eje activo de cada proyecto (`active_alignment_id`) y de qué eje, PK y desplazamiento
 *   viene un punto calculado sobre un eje (`axis_name`, `axis_chainage` y `axis_offset`, que admiten NULL). Migración de Room.
 * - v21 (S054): lo que guarda un punto replanteado por PK y un punto medido desde «Analizar» (columnas que admiten NULL). Migración de Room.
 * - v22 (S045): los rásteres de cotas como superficies: `surfaces.kind` (`TIN` por defecto) y los datos de la malla (columnas que admiten
 *   NULL) y la tabla `raster_tiles` con sus bloques. Migración de Room.
 * - v23 (S020): los desplazamientos de base de cada proyecto (`base_shifts`) y, en cada punto, el vector con que se midió, el punto
 *   conocido y la punta sin desplazar (columnas que admiten NULL). Migración de Room.
 * - v24 (S017b): las fotos de los puntos (`point_photos`) y sus sucesos (`photo_events`). Solo añade tablas: migración de Room.
 * - v27 (S052): las tomas lineales (`linear_surveys`) y los puntos de cada una (`linear_survey_points`). Solo añade tablas: migración de Room.
 * - v28 (S023): las bibliotecas de códigos (`code_libraries`, `library_codes`, `code_attributes` y `attribute_options`); una fila con `project_id`
 *   es la copia que lleva un proyecto. Solo añade tablas: migración de Room.
 * - v29 (S024): el código de cada punto (`code`, `code_description`, `code_color`, `code_symbol`) y sus atributos (`point_attributes`), lo mismo en
 *   el historial (`point_history_attributes`), el símbolo de cada código y `project_codes_of` en `code_libraries` (los códigos creados en un
 *   proyecto). Columnas que admiten NULL o con valor por defecto y tablas nuevas: migración de Room.
 * - v30 (S025): las líneas del proyecto (`project_lines`, `line_vertices`, `line_attributes`) y su historial (`line_history`,
 *   `line_history_attributes` y `line_history_vertices`). Solo añade tablas: migración de Room.
 */
@Database(
    entities = [
        ProjectEntity::class, ProjectFolderEntity::class, NtripProfileEntity::class, MapViewportEntity::class, PointEntity::class,
        PointHistoryEntity::class, PointNmeaEntity::class, CadDrawingEntity::class, CadLayerEntity::class, CadChunkEntity::class,
        SurfaceEntity::class, SurfaceTileEntity::class, AntennaProfileEntity::class, SavedPoleEntity::class, ReceiverGearEntity::class,
        CadChunkAttributesEntity::class, AlignmentEntity::class, RasterTileEntity::class, BaseShiftEntity::class,
        PointPhotoEntity::class, PhotoEventEntity::class, SectionTemplateEntity::class, SectionProfileEntity::class,
        SuperelevationRowEntity::class, SlopeRuleEntity::class, LinearSurveyEntity::class, LinearSurveyPointEntity::class,
        CodeLibraryEntity::class, LibraryCodeEntity::class, CodeAttributeEntity::class, AttributeOptionEntity::class,
        PointAttributeEntity::class, PointHistoryAttributeEntity::class, ProjectLineEntity::class, LineVertexEntity::class,
        LineAttributeEntity::class, LineHistoryEntity::class, LineHistoryAttributeEntity::class, LineHistoryVertexEntity::class,
    ],
    version = TopoPhoneDatabase.VERSION,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(
            from = 1,
            to = 2,
        ), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = TopoPhoneDatabase.PointsV6::class),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8, spec = TopoPhoneDatabase.PointsV8::class),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 11, to = 12),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 13, to = 14),
        AutoMigration(from = 14, to = 15),
        AutoMigration(from = 15, to = 16),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 27, to = 28),
        AutoMigration(from = 28, to = 29),
        AutoMigration(from = 29, to = 30),
    ],
)
abstract class TopoPhoneDatabase : RoomDatabase() {

    abstract fun projects(): ProjectDao

    abstract fun ntripProfiles(): NtripProfileDao

    abstract fun mapViewports(): MapViewportDao

    abstract fun points(): PointDao

    abstract fun cad(): CadDao

    abstract fun surfaces(): SurfaceDao

    abstract fun gear(): GearDao

    abstract fun alignments(): AlignmentDao

    abstract fun baseShifts(): BaseShiftDao

    abstract fun photos(): PhotoDao

    abstract fun sections(): SectionDao

    abstract fun slopes(): SlopeDao

    abstract fun linearSurveys(): LinearSurveyDao

    abstract fun codeLibraries(): CodeLibraryDao

    companion object {
        const val NAME = "topophone.db"

        /** Versión actual del esquema; una copia de una versión mayor no se restaura (S013/RF-13). */
        const val VERSION = 30

        /** WAL y `synchronous=FULL`: lo confirmado sobrevive aunque Android cierre la app justo después (RNF-T6). */
        fun build(context: Context, name: String = NAME): TopoPhoneDatabase =
            Room.databaseBuilder(context.applicationContext, TopoPhoneDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(FullSync)
                .build()
    }

    /** v5 → v6 (S011): los puntos de S010 son medidos y sus coordenadas actuales son las de su medida. */
    class PointsV6 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "UPDATE points SET measured_ground_lat = ground_lat, measured_ground_lon = ground_lon, " +
                    "measured_ground_h = ground_h, measured_easting = easting, measured_northing = northing, " +
                    "measured_height = height WHERE origin = 'MEASURED'",
            )
        }
    }

    /** v7 → v8 (S016): los puntos medidos hasta ahora se calcularon con el jalón vertical (RNF-T6). */
    class PointsV8 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE points SET tilt_mode = 'VERTICAL', tilt_verified = 0 WHERE origin = 'MEASURED'")
        }
    }

    /** Las escrituras van por la conexión principal: se pone en ella la sincronización completa. */
    private object FullSync : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA synchronous = FULL")
        }
    }
}

package es.topophone.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import es.topophone.data.cad.CadChunkEntity
import es.topophone.data.cad.CadDao
import es.topophone.data.cad.CadDrawingEntity
import es.topophone.data.cad.CadLayerEntity
import es.topophone.data.map.MapViewportDao
import es.topophone.data.map.MapViewportEntity
import es.topophone.data.ntrip.NtripProfileDao
import es.topophone.data.ntrip.NtripProfileEntity
import es.topophone.data.point.PointDao
import es.topophone.data.point.PointEntity
import es.topophone.data.point.PointHistoryEntity
import es.topophone.data.point.PointNmeaEntity
import es.topophone.data.project.ProjectDao
import es.topophone.data.project.ProjectEntity
import es.topophone.data.project.ProjectFolderEntity

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
 */
@Database(
    entities = [
        ProjectEntity::class, ProjectFolderEntity::class, NtripProfileEntity::class, MapViewportEntity::class, PointEntity::class,
        PointHistoryEntity::class, PointNmeaEntity::class, CadDrawingEntity::class, CadLayerEntity::class, CadChunkEntity::class,
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
    ],
)
abstract class TopoPhoneDatabase : RoomDatabase() {

    abstract fun projects(): ProjectDao

    abstract fun ntripProfiles(): NtripProfileDao

    abstract fun mapViewports(): MapViewportDao

    abstract fun points(): PointDao

    abstract fun cad(): CadDao

    companion object {
        const val NAME = "topophone.db"

        /** Versión actual del esquema; una copia de una versión mayor no se restaura (S013/RF-13). */
        const val VERSION = 13

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

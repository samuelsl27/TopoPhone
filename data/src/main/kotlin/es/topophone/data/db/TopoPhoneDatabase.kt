package es.topophone.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import es.topophone.data.map.MapViewportDao
import es.topophone.data.map.MapViewportEntity
import es.topophone.data.ntrip.NtripProfileDao
import es.topophone.data.ntrip.NtripProfileEntity
import es.topophone.data.point.PointDao
import es.topophone.data.point.PointEntity
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
 */
@Database(
    entities = [ProjectEntity::class, ProjectFolderEntity::class, NtripProfileEntity::class, MapViewportEntity::class, PointEntity::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(
            from = 1,
            to = 2,
        ), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5),
    ],
)
abstract class TopoPhoneDatabase : RoomDatabase() {

    abstract fun projects(): ProjectDao

    abstract fun ntripProfiles(): NtripProfileDao

    abstract fun mapViewports(): MapViewportDao

    abstract fun points(): PointDao

    companion object {
        const val NAME = "topophone.db"

        /** WAL y `synchronous=FULL`: lo confirmado sobrevive aunque Android cierre la app justo después (RNF-T6). */
        fun build(context: Context, name: String = NAME): TopoPhoneDatabase =
            Room.databaseBuilder(context.applicationContext, TopoPhoneDatabase::class.java, name)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(FullSync)
                .build()
    }

    /** Las escrituras van por la conexión principal: se pone en ella la sincronización completa. */
    private object FullSync : Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.execSQL("PRAGMA synchronous = FULL")
        }
    }
}

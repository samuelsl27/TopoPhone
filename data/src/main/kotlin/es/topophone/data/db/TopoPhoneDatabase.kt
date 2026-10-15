package es.topophone.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import es.topophone.data.project.ProjectDao
import es.topophone.data.project.ProjectEntity
import es.topophone.data.project.ProjectFolderEntity

/**
 * Base de datos de la app (ADR-8). El esquema crece spec a spec: cada versión nueva lleva su
 * migración y su test contra la anterior, con los esquemas exportados en `data/schemas/` (RNF-T6).
 */
@Database(entities = [ProjectEntity::class, ProjectFolderEntity::class], version = 1, exportSchema = true)
abstract class TopoPhoneDatabase : RoomDatabase() {

    abstract fun projects(): ProjectDao

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

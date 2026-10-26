package es.topophone.data.backup

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import es.topophone.data.db.TopoPhoneDatabase
import java.io.File

/** Qué filas entran en la instantánea: todas (copia completa) o las de un proyecto (paquete). */
sealed interface SnapshotScope {
    data object All : SnapshotScope

    data class Project(val id: String) : SnapshotScope
}

/** Proyectos activos y sus puntos activos, para el resumen de la restauración (S013/RF-6). */
data class SnapshotCounts(val projects: Int, val points: Int)

/**
 * Copia las tablas de la app a una base de datos aparte con el mismo esquema de Room (S013/RF-2, RF-24; plan, A1).
 *
 * La lectura va por una conexión propia de solo lectura dentro de un `SAVEPOINT`, que fuera de una transacción abre
 * una transacción diferida: con WAL ve un estado coherente de los datos y no bloquea al escritor de Room, así que un
 * punto se puede guardar mientras dura la copia (RNF de la spec). (`beginTransaction` de Android abriría una
 * transacción de escritura.) Los valores pasan columna a columna con su tipo: los `REAL` nunca pasan por texto
 * (constitución nº 2). Las contraseñas NTRIP no salen: queda una marca vacía si el perfil la tenía (S013/RF-3, RF-15).
 *
 * [onSnapshotOpen] se llama con la instantánea ya fijada; sirve para probar que se puede escribir a la vez.
 */
class DatabaseSnapshot(private val context: Context, private val source: File, private val onSnapshotOpen: () -> Unit = {}) {
    fun copy(target: File, scope: SnapshotScope): SnapshotCounts {
        target.delete()
        val out = Room.databaseBuilder(context.applicationContext, TopoPhoneDatabase::class.java, target.absolutePath)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            val sink = out.openHelper.writableDatabase
            return SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { from ->
                from.execSQL("SAVEPOINT $SAVEPOINT")
                try {
                    sink.beginTransaction()
                    try {
                        copyTables(from, sink, scope).also { sink.setTransactionSuccessful() }
                    } finally {
                        sink.endTransaction()
                    }
                } finally {
                    from.execSQL("RELEASE $SAVEPOINT")
                }
            }
        } finally {
            out.close()
        }
    }

    private fun copyTables(from: SQLiteDatabase, sink: SupportSQLiteDatabase, scope: SnapshotScope): SnapshotCounts {
        val activeProjects = mutableSetOf<String>()
        var activePoints = 0
        var first = true
        val opened = {
            if (first) {
                first = false
                onSnapshotOpen()
            }
        }
        for (table in TABLES) {
            val where = when (scope) {
                SnapshotScope.All -> ""
                is SnapshotScope.Project -> table.projectFilter ?: continue
            }
            val args = if (scope is SnapshotScope.Project) arrayOf(scope.id) else null
            copyRows(from, sink, table.name, where, args) { values ->
                opened()
                when (table.name) {
                    "projects" -> {
                        if (scope is SnapshotScope.Project) values.putNull("folder_id") // El paquete no lleva carpetas.
                        if (values.get("deleted_at") == null) activeProjects += values.getAsString("id")
                    }
                    "points" -> if (values.getAsString("trash_key").isEmpty() && values.getAsString("project_id") in activeProjects) {
                        activePoints++
                    }
                    "ntrip_profiles" -> PASSWORD_COLUMNS.forEach { column ->
                        if (values.get(column) != null) values.put(column, ByteArray(0)) // S013/RF-3: solo la marca.
                    }
                }
            }
            opened()
        }
        return SnapshotCounts(projects = activeProjects.size, points = activePoints)
    }

    /** Una tabla de la app; [projectFilter] es la condición del paquete, o null si la tabla no va en el paquete. */
    class Table(val name: String, val projectFilter: String?)

    companion object {
        private const val SAVEPOINT = "backup_snapshot"

        /**
         * Copia las filas de [table] de [from] a [sink] columna a columna con su tipo; [transform] puede cambiar cada fila
         * antes de insertarla. También la usan la restauración y los paquetes.
         */
        internal fun copyRows(
            from: SQLiteDatabase,
            sink: SupportSQLiteDatabase,
            table: String,
            where: String = "",
            args: Array<String>? = null,
            transform: (ContentValues) -> Unit = {},
        ): Int = from.rawQuery("SELECT * FROM $table $where", args).use { copyRows(it, sink, table, transform) }

        /** Como el anterior, con las filas de [cursor] (por ejemplo, de la propia base de datos de la app). */
        internal fun copyRows(cursor: Cursor, sink: SupportSQLiteDatabase, table: String, transform: (ContentValues) -> Unit = {}): Int {
            var count = 0
            val columns = cursor.columnNames
            while (cursor.moveToNext()) {
                val values = ContentValues(columns.size)
                columns.forEachIndexed { i, column -> values.put(column, cursor, i) }
                transform(values)
                sink.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
                count++
            }
            return count
        }

        private fun ContentValues.put(column: String, cursor: Cursor, i: Int) {
            when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> putNull(column)
                Cursor.FIELD_TYPE_INTEGER -> put(column, cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> put(column, cursor.getDouble(i))
                Cursor.FIELD_TYPE_BLOB -> put(column, cursor.getBlob(i))
                else -> put(column, cursor.getString(i))
            }
        }
        private val PASSWORD_COLUMNS = listOf("password_iv", "password_ct")

        /**
         * Las tablas de la app, de padres a hijos (en este orden se insertan; al revés se borran). Cada spec que añada
         * una tabla la añade aquí (S017b fotos, S023 códigos…).
         */
        val TABLES = listOf(
            Table("project_folders", projectFilter = null),
            Table("projects", "WHERE id = ?"),
            Table("map_viewports", "WHERE project_id = ?"),
            Table("points", "WHERE project_id = ?"),
            Table("point_nmea", "WHERE point_id IN (SELECT id FROM points WHERE project_id = ?)"),
            Table("point_history", "WHERE point_id IN (SELECT id FROM points WHERE project_id = ?)"),
            Table("cad_drawings", "WHERE project_id = ? AND status = 'READY'"),
            Table("cad_layers", "WHERE drawing_id IN (SELECT id FROM cad_drawings WHERE project_id = ? AND status = 'READY')"),
            Table(
                "cad_chunks",
                "WHERE layer_id IN (SELECT l.id FROM cad_layers l JOIN cad_drawings d ON d.id = l.drawing_id " +
                    "WHERE d.project_id = ? AND d.status = 'READY')",
            ),
            Table("ntrip_profiles", projectFilter = null),
        )
    }
}

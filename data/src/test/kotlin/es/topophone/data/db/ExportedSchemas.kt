package es.topophone.data.db

import android.database.sqlite.SQLiteDatabase
import java.io.File
import org.json.JSONObject

/** Bases de datos tal como las describen los esquemas exportados de `data/schemas/`, para probar versiones antiguas. */
object ExportedSchemas {
    /** Crea en [file] la base de datos de la versión [version] del esquema, vacía, sin room-testing. */
    fun create(file: File, version: Int) {
        val schema = JSONObject(File("schemas/${TopoPhoneDatabase::class.java.name}/$version.json").readText()).getJSONObject("database")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = schema.getInt("version")
        }
    }
}

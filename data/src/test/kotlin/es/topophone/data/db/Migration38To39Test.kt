package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** De la v38 a la v39 (S058): las columnas del plano de terreno; los proyectos, los puntos y el historial se conservan. */
@RunWith(AndroidJUnit4::class)
class Migration38To39Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun deleteDatabase() {
        context.deleteDatabase(DB)
    }

    /** Inserta una fila con solo las columnas obligatorias y sin valor por defecto; [values] manda. */
    private fun SQLiteDatabase.insertMinimal(table: String, values: Map<String, Any>) {
        val columns = mutableListOf<String>()
        val args = mutableListOf<Any>()
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)
                val type = cursor.getString(2).uppercase()
                val notNull = cursor.getInt(3) == 1
                val hasDefault = !cursor.isNull(4)
                when {
                    name in values -> {
                        columns += name
                        args += values.getValue(name)
                    }
                    notNull && !hasDefault -> {
                        columns += name
                        args += when {
                            "INT" in type -> 0
                            "REAL" in type -> 0.0
                            else -> "x"
                        }
                    }
                }
            }
        }
        execSQL("INSERT INTO $table (${columns.joinToString()}) VALUES (${columns.joinToString { "?" }})", args.toTypedArray())
    }

    @Test
    fun `S058 RNF de la v38 a la v39 todo se conserva y el plano de terreno nace vacio`() {
        ExportedSchemas.create(context.getDatabasePath(DB), 38)
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v38 ->
            v38.insertMinimal("projects", mapOf("id" to "p1", "name" to "Obra", "crs" to "ETRS89_UTM30"))
            v38.insertMinimal("points", mapOf("id" to "pt1", "project_id" to "p1", "name" to "P1"))
            v38.insertMinimal("project_crs_history", mapOf("id" to "h1", "project_id" to "p1", "kind" to "CHANGE"))
        }

        val db = TopoPhoneDatabase.build(context, DB)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.version shouldBe TopoPhoneDatabase.VERSION
            sqlite.query("SELECT name, terrain_origin_lat, terrain_origin_lon, terrain_factor FROM projects WHERE id = 'p1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "Obra"
                it.isNull(1) shouldBe true
                it.isNull(2) shouldBe true
                it.isNull(3) shouldBe true
            }
            sqlite.query("SELECT name FROM points WHERE id = 'pt1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "P1"
            }
            sqlite.query("SELECT kind, from_terrain, to_terrain FROM project_crs_history WHERE id = 'h1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "CHANGE"
                it.isNull(1) shouldBe true
                it.isNull(2) shouldBe true
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-38-39.db"
    }
}

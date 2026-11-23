package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** De la v39 a la v40 (S039): las tablas de localizaciones y `points.localization_id`; los proyectos y los puntos se conservan. */
@RunWith(AndroidJUnit4::class)
class Migration39To40Test {
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
    fun `S039 RNF de la v39 a la v40 todo se conserva y las localizaciones nacen vacias`() {
        ExportedSchemas.create(context.getDatabasePath(DB), 39)
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v39 ->
            v39.insertMinimal("projects", mapOf("id" to "p1", "name" to "Obra", "crs" to "ETRS89_UTM30"))
            v39.insertMinimal("points", mapOf("id" to "pt1", "project_id" to "p1", "name" to "P1"))
        }

        val db = TopoPhoneDatabase.build(context, DB)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.version shouldBe TopoPhoneDatabase.VERSION
            sqlite.query("SELECT name FROM projects WHERE id = 'p1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "Obra"
            }
            sqlite.query("SELECT name, localization_id FROM points WHERE id = 'pt1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "P1"
                it.isNull(1) shouldBe true
            }
            for (table in listOf("localizations", "localization_pairs", "localized_crs")) {
                sqlite.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst() shouldBe true
                    it.getInt(0) shouldBe 0
                }
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-39-40.db"
    }
}

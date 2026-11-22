package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** De la v37 a la v38 (S035): las tablas de Mis SC y del historial del proyecto, y la ondulación constante; los proyectos y los puntos se conservan. */
@RunWith(AndroidJUnit4::class)
class Migration37To38Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun deleteDatabase() {
        context.deleteDatabase(DB)
    }

    /** Inserta una fila con solo las columnas obligatorias y sin valor por defecto, con un valor cualquiera del tipo de cada una; [values] manda. */
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
    fun `S035 RNF al pasar de la v37 a la v38 los proyectos y los puntos se conservan y aparecen las tablas nuevas`() {
        ExportedSchemas.create(context.getDatabasePath(DB), 37)
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v37 ->
            v37.insertMinimal("projects", mapOf("id" to "p1", "name" to "Obra", "crs" to "ETRS89_UTM30", "crs_definition" to "texto"))
            v37.insertMinimal("points", mapOf("id" to "pt1", "project_id" to "p1", "name" to "P1"))
        }

        val db = TopoPhoneDatabase.build(context, DB)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.version shouldBe TopoPhoneDatabase.VERSION
            sqlite.query("SELECT name, crs, crs_definition, geoid_constant_m FROM projects WHERE id = 'p1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "Obra"
                it.getString(1) shouldBe "ETRS89_UTM30"
                it.getString(2) shouldBe "texto"
                it.isNull(3) shouldBe true
            }
            sqlite.query("SELECT name FROM points WHERE id = 'pt1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "P1"
            }
            sqlite.query("SELECT COUNT(*) FROM custom_crs").use {
                it.moveToFirst() shouldBe true
                it.getInt(0) shouldBe 0
            }
            sqlite.query("SELECT COUNT(*) FROM project_crs_history").use {
                it.moveToFirst() shouldBe true
                it.getInt(0) shouldBe 0
            }
            runCatching { sqlite.query("SELECT geoid_constant_m FROM projects").use { it.moveToFirst() } }.exceptionOrNull().shouldBeNull()
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-37-38.db"
    }
}

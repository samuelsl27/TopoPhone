package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * De la v40 a la v41 (S061): `surfaces.visible` y la tabla `surface_outlines`. La superficie activa de cada proyecto queda visible y
 * las demás ocultas; todo lo demás se conserva.
 */
@RunWith(AndroidJUnit4::class)
class Migration40To41Test {
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
    fun `S061 RF-5 de la v40 a la v41 la superficie activa queda visible y las demas ocultas`() {
        ExportedSchemas.create(context.getDatabasePath(DB), 40)
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v40 ->
            v40.insertMinimal("projects", mapOf("id" to "p1", "name" to "Obra", "crs" to "ETRS89_UTM30"))
            v40.insertMinimal("projects", mapOf("id" to "p2", "name" to "Otra", "crs" to "ETRS89_UTM30"))
            for ((id, project) in listOf("s1" to "p1", "s2" to "p1", "s3" to "p2")) {
                v40.insertMinimal(
                    "surfaces",
                    mapOf("id" to id, "project_id" to project, "name" to "", "file_name" to "$id.xml", "status" to "READY"),
                )
            }
            v40.execSQL("UPDATE projects SET active_surface_id = 's1' WHERE id = 'p1'")
        }

        val db = TopoPhoneDatabase.build(context, DB)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.version shouldBe TopoPhoneDatabase.VERSION
            sqlite.query("SELECT id, visible, name, file_name FROM surfaces ORDER BY id").use {
                val rows = generateSequence {
                    if (it.moveToNext()) listOf(it.getString(0), it.getInt(1), it.getString(2), it.getString(3)) else null
                }.toList()
                // Solo cambia la visibilidad: el nombre vacío se queda tal cual (el nombre por defecto se pone al leer, RF-2).
                rows shouldBe listOf(listOf("s1", 1, "", "s1.xml"), listOf("s2", 0, "", "s2.xml"), listOf("s3", 0, "", "s3.xml"))
            }
            sqlite.query("SELECT active_surface_id FROM projects WHERE id = 'p1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "s1"
            }
            sqlite.query("SELECT COUNT(*) FROM surface_outlines").use {
                it.moveToFirst() shouldBe true
                it.getInt(0) shouldBe 0
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-40-41.db"
    }
}

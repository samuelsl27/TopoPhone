package es.topophone.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** De la v41 a la v42 (S062): `points.stake_surface_only`, que nace vacía; los puntos se conservan. */
@RunWith(AndroidJUnit4::class)
class Migration41To42Test {
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
    fun `S062 RF-17 de la v41 a la v42 los puntos se conservan y no son replanteos de superficie`() {
        ExportedSchemas.create(context.getDatabasePath(DB), 41)
        SQLiteDatabase.openDatabase(context.getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE).use { v41 ->
            v41.insertMinimal("projects", mapOf("id" to "p1", "name" to "Obra", "crs" to "ETRS89_UTM30"))
            v41.insertMinimal("points", mapOf("id" to "pt1", "project_id" to "p1", "name" to "12_stkd", "stake_design_name" to "12"))
        }

        val db = TopoPhoneDatabase.build(context, DB)
        try {
            val sqlite = db.openHelper.writableDatabase
            sqlite.version shouldBe TopoPhoneDatabase.VERSION
            sqlite.query("SELECT name, stake_design_name, stake_surface_only FROM points WHERE id = 'pt1'").use {
                it.moveToFirst() shouldBe true
                it.getString(0) shouldBe "12_stkd"
                it.getString(1) shouldBe "12"
                it.isNull(2) shouldBe true
            }
        } finally {
            db.close()
        }
    }

    private companion object {
        const val DB = "migration-41-42.db"
    }
}

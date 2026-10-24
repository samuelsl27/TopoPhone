package es.topophone.data.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointChange
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.point.PrecisionFailure
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.map.MapViewportEntity
import es.topophone.data.ntrip.NtripProfileEntity
import es.topophone.data.point.RoomPointRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * Una base de datos con un poco de todo para las copias (S013): carpeta, proyectos (uno en la papelera), puntos medidos,
 * manuales, editados con historial, ocultos y en la papelera, encuadre del mapa y perfiles NTRIP con y sin contraseña.
 * Las coordenadas tienen todos sus decimales, para ver que nada se redondea (constitución nº 2).
 */
class BackupFixture(val db: TopoPhoneDatabase, var now: Long = 1_792_346_000_000) {
    val projects = RoomProjectRepository(db, clock = { now })
    val points = RoomPointRepository(db, clock = { now })

    lateinit var folderId: String
    lateinit var streetId: String
    lateinit var portId: String
    lateinit var oldId: String

    fun fill(): BackupFixture = runBlocking {
        folderId = (projects.createFolder("Obras") as SaveResult.Saved).id
        streetId = (projects.create(ProjectDefaults().toDraft().copy(name = "Calle Mayor 12"), folderId) as SaveResult.Saved).id
        portId = (projects.create(ProjectDefaults().toDraft().copy(name = "Puerto")) as SaveResult.Saved).id
        oldId = (projects.create(ProjectDefaults().toDraft().copy(name = "Viejo")) as SaveResult.Saved).id

        points.create(measured("1", streetId))
        points.create(manual("E1", streetId))
        val second = measured("2", streetId)
        points.create(second)
        now += 1_000
        points.update(second.copy(description = "Bordillo, \"norte\"", edited = true), PointChange.EDIT)
        points.create(measured("3", streetId).copy(hidden = true))
        points.create(measured("4", streetId))
        points.moveToTrash(listOf("id-4"))
        points.create(measured("9", oldId))
        projects.moveToTrash(oldId)

        db.mapViewports().upsert(MapViewportEntity(streetId, 40.416775123456789, -3.703790987654321, 17.25))
        db.ntripProfiles().insert(profile("ntrip-ign", "IGN", password = true))
        db.ntripProfiles().insert(profile("ntrip-libre", "Libre", password = false))
        this@BackupFixture
    }

    fun measured(name: String, projectId: String, createdAtMs: Long = now) = Point.measured(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "",
        measurement = Measurement(
            antenna = GeodeticPosition(40.416815093712345, -3.7035330167891234, 701.35712345678),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.0091234567, 0.0140987654, MeasurementMode.AVERAGED, 20, 6, fromReplay = false),
            rawNmea = "\$GNGGA,060231.00,4025.00890,N,00342.21198,W,4,24,0.6,699.4,M,51.9,M,1.0,0000*5C\n\$GNGST,1*00",
            outOfTolerance = setOf(PrecisionFailure.HORIZONTAL),
            measuredFromMs = createdAtMs - 20_000,
            measuredToMs = createdAtMs,
            coordinates = PointCoordinates(
                GeodeticPosition(40.416815093712345, -3.7035330167891234, 699.42312345678),
                440_313.12211953723,
                4_474_258.8768800592,
                648.31136667548324,
            ),
        ),
        createdAtMs = createdAtMs,
    ).also { now += 1_000 }

    private fun manual(name: String, projectId: String) = Point(
        id = "id-$name",
        projectId = projectId,
        name = name,
        description = "Pozo",
        origin = PointOrigin.MANUAL,
        coordinates = PointCoordinates(GeodeticPosition(40.416825704123, -3.703452058456, null), 440_320.0, 4_474_260.0, null),
        measurement = null,
        typed = null,
        edited = false,
        hidden = false,
        copyOf = null,
        createdAtMs = now,
        deletedAtMs = null,
    ).also { now += 1_000 }

    private fun profile(id: String, name: String, password: Boolean) = NtripProfileEntity(
        id = id,
        name = name,
        host = "ergnss-tr.ign.es",
        port = 2101,
        useTls = false,
        username = "ana",
        mountpoint = "VRS3M",
        mountpointNeedsGga = true,
        passwordIv = if (password) ByteArray(12) { it.toByte() } else null,
        passwordCiphertext = if (password) ByteArray(24) { (it * 7).toByte() } else null,
        createdAt = now,
        modifiedAt = now,
    )

    companion object {
        /** Las tablas que guarda la app, en el orden en que se copian. */
        val TABLES = listOf("project_folders", "projects", "map_viewports", "points", "point_nmea", "point_history", "ntrip_profiles")

        /** Todas las filas de [table] en [file], ordenadas por su primera columna; los BLOB como listas para compararlos. */
        fun rows(file: File, table: String): List<List<Any?>> {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            return db.use {
                it.rawQuery("SELECT * FROM $table ORDER BY 1", null).use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { i -> cursor.value(i) })
                    }
                }
            }
        }

        fun columns(file: File, table: String): List<String> =
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
                it.rawQuery("SELECT * FROM $table LIMIT 0", null).use { cursor -> cursor.columnNames.toList() }
            }

        private fun Cursor.value(i: Int): Any? = when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_BLOB -> getBlob(i).toList()
            else -> getString(i)
        }
    }
}

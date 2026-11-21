package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeodesyFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** Cada SC proyectado de la biblioteca frente a pyproj/PROJ con tres puntos (S034/RF-4; constitución nº 2: ≤ 1 mm, ida y vuelta ≤ 0,1 mm). */
class CatalogVectorsTest {
    private val catalog = CrsCatalog.bundled

    private data class Vector(val epsg: Int, val latitudeDeg: Double, val longitudeDeg: Double, val eastingM: Double, val northingM: Double)

    private fun vectors(): List<Vector> = GeodesyFixtures.names("catalogo_").filterNot { "transformaciones" in it }.flatMap { name ->
        GeodesyFixtures.rows(name).map { Vector(it[0].toInt(), it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toDouble()) }
    }

    @Test
    fun `S034 RF-4 los SC proyectados del catalogo dan lo de pyproj a 1 mm y cierran la ida y vuelta a 0,1 mm`() {
        val all = vectors()
        all.size shouldBeGreaterThan 13500

        val failures = mutableListOf<String>()
        val projections = HashMap<Int, es.topophone.core.geodesy.projection.MapProjection?>()
        all.forEach { v ->
            val crs = catalog.crsByEpsg(v.epsg) ?: run {
                failures += "EPSG:${v.epsg} no está en el catálogo"
                return@forEach
            }
            val projection = projections.getOrPut(v.epsg) { CrsBuilder.projection(crs, catalog.datums.getValue(crs.datumCode)) }
            if (projection == null) {
                failures += "EPSG:${v.epsg}: sin proyección"
                return@forEach
            }
            val point = projection.forward(v.latitudeDeg, v.longitudeDeg)
            if (point == null) {
                failures += "EPSG:${v.epsg} (${v.latitudeDeg}, ${v.longitudeDeg}): sin imagen"
                return@forEach
            }
            val error = hypot(point.eastingM - v.eastingM, point.northingM - v.northingM)
            if (error > 0.001) failures += "EPSG:${v.epsg} (${v.latitudeDeg}, ${v.longitudeDeg}): ${"%.4f".format(error)} m"
            val back = projection.inverse(v.eastingM, v.northingM)
            val again = back?.let { projection.forward(it.latitudeDeg, it.longitudeDeg) }
            if (again == null || hypot(again.eastingM - v.eastingM, again.northingM - v.northingM) > 0.0001) {
                failures += "EPSG:${v.epsg}: la ida y vuelta no cierra"
            }
        }
        java.io.File("build/catalog-vector-failures.txt").writeText(failures.joinToString(System.lineSeparator()))
        failures.shouldBeEmpty()
    }
}

package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/** El paso a terreno frente a pyproj/PROJ (S058/RF-7; constitución nº 2: ≤ 1 mm, ida y vuelta ≤ 0,1 mm), con tres SC y tres puntos cada uno. */
class TerrainVectorsTest {

    private fun crsOf(epsg: Int, at: GeoPoint): ProjectCrs = when (epsg) {
        25829 -> SpainCrs.ETRS89_UTM29
        25830 -> SpainCrs.ETRS89_UTM30
        else -> (CrsCatalog.bundled.build(epsg, CorrectionsDatum.ETRS89, at) as CrsBuildResult.Ready).let {
            LibraryCrs(it.definition, CorrectionsDatum.ETRS89)
        }
    }

    @Test
    fun `S058 RF-7 el origen y las coordenadas de terreno dan lo de pyproj a 1 mm y la ida y vuelta cierra a 0,1 mm`() {
        val rows = SurveyFixtures.rows("terreno.csv")
        rows.size shouldBeGreaterThan 8

        val failures = mutableListOf<String>()
        rows.forEach { row ->
            val epsg = row[0].toInt()
            val latitude = row[1].toDouble()
            val longitude = row[2].toDouble()
            val factor = row[4].toDouble()
            val label = "EPSG:$epsg #${row[7]}"
            val frame = TerrainFrame.of(
                TerrainPlane(latitude, longitude, factor),
                crsOf(epsg, GeoPoint(latitude, longitude)),
            ).shouldNotBeNull()

            val origin = hypot(frame.originEasting - row[5].toDouble(), frame.originNorthing - row[6].toDouble())
            if (origin > 0.001) failures += "$label: el origen se aleja ${"%.5f".format(origin)} m de pyproj"
            val e = row[8].toDouble()
            val n = row[9].toDouble()
            val t = frame.toTerrain(e, n)
            val error = hypot(t.easting - row[10].toDouble(), t.northing - row[11].toDouble())
            if (error > 0.001) failures += "$label: ${"%.5f".format(error)} m frente a pyproj"
            val back = frame.toGrid(t.easting, t.northing)
            if (hypot(back.easting - e, back.northing - n) > 0.0001) failures += "$label: la ida y vuelta no cierra"
        }
        failures.shouldBeEmpty()
    }
}

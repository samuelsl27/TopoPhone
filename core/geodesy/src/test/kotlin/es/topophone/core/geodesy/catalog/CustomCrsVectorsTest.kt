package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.geodesy.GeodeticPoint
import es.topophone.core.geodesy.datum.ClasspathGridSource
import es.topophone.core.geodesy.datum.TransformResult
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.cos
import kotlin.math.hypot
import org.junit.jupiter.api.Test

/**
 * Un SC personalizado frente a pyproj/PROJ (S035/RF-3, RF-4; constitución nº 2): con los parámetros sueltos de `personalizado.csv` da lo mismo a 1 mm y
 * la ida y vuelta cierra a 0,1 mm; y partiendo de un SC de la biblioteca da exactamente lo que este.
 */
class CustomCrsVectorsTest {
    private val catalog = CrsCatalog.bundled
    private val grids = ClasspathGridSource("/shift/")

    private fun transformation(type: String, v: List<Double>): CustomTransformation {
        val values = HelmertValues(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        return when (type) {
            "nula" -> CustomTransformation.None
            "helmert3" -> CustomTransformation.Helmert(HelmertKind.TRANSLATIONS_3, RotationConvention.POSITION_VECTOR, values)
            "helmert7pv" -> CustomTransformation.Helmert(HelmertKind.SEVEN, RotationConvention.POSITION_VECTOR, values)
            "helmert7cf" -> CustomTransformation.Helmert(HelmertKind.SEVEN, RotationConvention.COORDINATE_FRAME, values)
            else -> error("Transformación desconocida: $type")
        }
    }

    private fun definitionOf(row: List<String>): CrsDefinition {
        val draft = CustomCrsDraft(
            name = row[0],
            semiMajorAxisM = row[3].toDouble(),
            inverseFlattening = row[4].toDouble(),
            method = row[1],
            parameters = row[2].split(';').associate { it.substringBefore('=') to it.substringAfter('=').toDouble() },
            transformation = transformation(row[5], row.subList(6, 13).map { it.toDouble() }),
        )
        return CustomCrsBuilder.build(draft, catalog).shouldBeInstanceOf<CustomBuildResult.Ready>().definition
    }

    /** Metros de diferencia entre dos posiciones geográficas cercanas, con la esfera media (sobra para comparar a 1 mm a distancias de milímetros). */
    private fun metres(a: GeodeticPoint, latitudeDeg: Double, longitudeDeg: Double): Double {
        val perDegree = 111_319.49
        return hypot(
            (a.latitudeDeg - latitudeDeg) * perDegree,
            (a.longitudeDeg - longitudeDeg) * perDegree * cos(Math.toRadians(latitudeDeg)),
        )
    }

    @Test
    fun `S035 RF-3 los SC personalizados dan lo de pyproj a 1 mm y cierran la ida y vuelta a 0,1 mm`() {
        val rows = GeodesyFixtures.rows("personalizado.csv")
        rows.size shouldBeGreaterThan 15

        val failures = mutableListOf<String>()
        rows.forEach { row ->
            val label = "${row[0]} #${row[13]}"
            val resolved = CrsDefinitions.resolve(definitionOf(row), grids).shouldNotBeNull()
            val projection = resolved.projection.shouldNotBeNull()
            val east = row[14].toDouble()
            val north = row[15].toDouble()
            val onDatum = projection.inverse(east, north).shouldNotBeNull()
            val frame = (
                resolved.transformation.forward(
                    GeodeticPoint(onDatum.latitudeDeg, onDatum.longitudeDeg, 0.0),
                ) as TransformResult.Ok
                ).point
            val error = metres(frame, row[17].toDouble(), row[16].toDouble())
            if (error > 0.001) failures += "$label: ${"%.5f".format(error)} m frente a pyproj"
            val back = (resolved.transformation.inverse(frame) as TransformResult.Ok).point
            val again = projection.forward(back.latitudeDeg, back.longitudeDeg).shouldNotBeNull()
            if (hypot(again.eastingM - east, again.northingM - north) > 0.0001) failures += "$label: la ida y vuelta no cierra"
        }
        failures.shouldBeEmpty()
    }

    private fun library(epsg: Int, corrections: CorrectionsDatum, at: GeoPoint): CrsDefinition =
        when (val r = catalog.build(epsg, corrections, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg: $r")
        }

    @Test
    fun `S035 RF-3 un SC personalizado con los parametros de uno de la biblioteca da lo mismo que el`() {
        val cases = listOf(
            Triple(25830, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)),
            Triple(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)),
            Triple(3844, CorrectionsDatum.ETRS89, GeoPoint(44.43, 26.1)),
            Triple(2100, CorrectionsDatum.ITRF_WGS84, GeoPoint(38.0, 23.5)),
            Triple(23030, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)), // con la rejilla SPED2ETV2
        )
        val failures = mutableListOf<String>()
        cases.forEach { (epsg, corrections, at) ->
            val lib = library(epsg, corrections, at)
            val custom = CustomCrsBuilder.build(CustomCrsDraft.of(lib).copy(name = "Mio"), catalog, isInstalled = { true })
            val definition = when (custom) {
                is CustomBuildResult.Ready -> custom.definition
                is CustomBuildResult.NeedsGrids -> custom.definition
                is CustomBuildResult.Invalid -> error("EPSG:$epsg: ${custom.errors}")
            }
            val a = CrsDefinitions.resolve(lib, grids).shouldNotBeNull()
            val b = CrsDefinitions.resolve(definition, grids).shouldNotBeNull()
            listOf(0.0, 0.3, -0.3).forEach { d ->
                val point = GeodeticPoint(at.latitudeDeg + d, at.longitudeDeg + d, 0.0)
                val fa = a.transformation.inverse(point)
                val fb = b.transformation.inverse(point)
                fa::class shouldBe fb::class
                if (fa is TransformResult.Ok && fb is TransformResult.Ok) {
                    val pa = a.projection?.forward(fa.point.latitudeDeg, fa.point.longitudeDeg)
                    val pb = b.projection?.forward(fb.point.latitudeDeg, fb.point.longitudeDeg)
                    if (pa == null ||
                        pb == null ||
                        hypot(pa.eastingM - pb.eastingM, pa.northingM - pb.northingM) > 0.001
                    ) {
                        failures += "EPSG:$epsg +$d"
                    }
                }
            }
        }
        failures.shouldBeEmpty()
    }
}

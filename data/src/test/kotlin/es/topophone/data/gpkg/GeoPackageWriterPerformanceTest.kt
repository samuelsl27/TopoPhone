package es.topophone.data.gpkg

import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.point.Measurement
import es.topophone.core.model.point.MeasurementMode
import es.topophone.core.model.point.Point
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.PointQuality
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.time.measureTimedValue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Exportar 10.000 puntos tarda menos de 5 s (S053, RNF). Robolectric sirve de cota; la medida en el móvil de referencia es de la demo. */
@RunWith(AndroidJUnit4::class)
class GeoPackageWriterPerformanceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val project = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    /** Un punto medido con todos los datos de calidad, a 1 m del anterior. */
    private fun point(index: Int) = Point.measured(
        id = "id-$index",
        projectId = "p1",
        name = index.toString(),
        description = "Punto $index de la obra",
        measurement = Measurement(
            antenna = GeodeticPosition(40.4168, -3.7038, 701.357),
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.0138, 0.021, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = 1_792_346_614_419 + index,
            measuredToMs = 1_792_346_639_419 + index,
            coordinates = PointCoordinates(
                GeodeticPosition(40.4168 + index * 1e-6, -3.7038, 699.423),
                440_000.0 + index,
                4_474_000.0 + index * 0.5,
                648.0 + index * 0.001,
            ),
        ),
        createdAtMs = 1_792_346_639_419 + index,
    )

    @Test
    fun `S053 RNF exportar 10000 puntos tarda menos de 5 s`() {
        val points = List(10_000, ::point)
        val into = File(folder.root, "diez-mil.gpkg")
        GeoPackageWriter().write(points.take(10), project, GpkgExportCrs.PROJECT, File(folder.root, "calentar.gpkg")) // calienta el código

        val timed = measureTimedValue { GeoPackageWriter().write(points, project, GpkgExportCrs.PROJECT, into) }

        println("Exportar 10.000 puntos: ${timed.duration.inWholeMilliseconds} ms")
        timed.value.shouldNotBeNull().points shouldBe 10_000
        timed.duration.inWholeMilliseconds shouldBeLessThan 5_000L
    }
}

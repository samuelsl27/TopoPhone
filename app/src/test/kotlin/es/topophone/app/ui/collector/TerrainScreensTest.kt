package es.topophone.app.ui.collector

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.SpainCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.ProjectPositionCalculator
import es.topophone.receiver.platform.Alerts
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.hypot
import org.junit.Test

/** Las distancias del panel de captura en terreno (S058/RF-9). */
class TerrainScreensTest {

    private val calculator = ProjectPositionCalculator()
    private val alerts = object : Alerts {
        override fun connectionLost() = Unit

        override fun correctionsLost() = Unit

        override fun pointSaved() = Unit

        override fun solutionLost() = Unit

        override fun tiltLost() = Unit
    }
    private val collector = AutoCollector(AppLog.NONE, alerts)

    private fun project(terrain: TerrainPlane?) = Project(
        "p", "Obra", "", "", SpainCrs.ETRS89_UTM30, LinearUnit.METRE, AngularUnit.DECIMAL_DEGREES, CorrectionsDatum.ETRS89,
        null, 0, 0, null, null, HeightSystem.ELLIPSOIDAL, null, terrain,
    )

    private fun epoch(lat: Double, lon: Double) = Epoch(
        0, null, GgaFix("101530.20", lat, lon, SolutionStatus.FIX, 24, 0.6, 650.0, 51.0, 1.0, ""), null, null, null, null, null, null,
        emptyMap(), emptyMap(), 0,
    )

    private fun gap(project: Project): Double {
        val a = collector.ground(epoch(40.41668, -3.70371), null, 2.0, project, calculator).shouldNotBeNull()
        val b = collector.ground(epoch(40.41678, -3.70361), null, 2.0, project, calculator).shouldNotBeNull()
        return hypot(b.east - a.east, b.north - a.north)
    }

    @Test
    fun `S058 RF-9 el modo automatico por distancia mide la de terreno cuando el proyecto esta en terreno`() {
        val grid = gap(project(null))

        val terrain = gap(project(TerrainPlane(40.41668, -3.70371, 1.0004)))

        terrain shouldBe (grid * 1.0004 plusOrMinus 1e-9)
    }

    @Test
    fun `S058 RF-9 en cuadricula el suelo del modo automatico no cambia`() {
        val a = collector.ground(epoch(40.41668, -3.70371), null, 2.0, project(null), calculator).shouldNotBeNull()
        val plane = collector.ground(epoch(40.41668, -3.70371), null, 2.0, project(TerrainPlane(40.41668, -3.70371, 1.0)), calculator)

        plane.shouldNotBeNull().east shouldBe (a.east plusOrMinus 1e-9)
    }
}

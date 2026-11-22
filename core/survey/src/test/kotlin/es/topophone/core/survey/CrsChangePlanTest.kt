package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.build
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Test

/** Qué puntos no se pueden calcular en el SC nuevo y qué SC son del mismo datum (S035/RF-17, RF-18, RF-19; RNF). */
class CrsChangePlanTest {
    private val catalog = CrsCatalog.bundled

    private fun library(
        epsg: Int,
        corrections: CorrectionsDatum = CorrectionsDatum.ETRS89,
        at: GeoPoint = GeoPoint(40.4, -3.7),
    ): LibraryCrs {
        val definition = when (val r = catalog.build(epsg, corrections, at)) {
            is CrsBuildResult.Ready -> r.definition
            is CrsBuildResult.NeedsGrids -> r.definition
            is CrsBuildResult.Unavailable -> error("EPSG:$epsg: $r")
        }
        return LibraryCrs(definition, corrections)
    }

    private val madrid = PlanPoint("p1", "P1", GeodeticPosition(40.4168, -3.7038, 667.0))
    private val paris = PlanPoint("p2", "P2", GeodeticPosition(48.8566, 2.3522, 100.0))

    @Test
    fun `S035 RF-18 los puntos que el SC nuevo calcula no dan ningun problema`() {
        val plan = CrsChange.plan(listOf(madrid), SpainCrs.ETRS89_UTM30, HeightSystem.ELLIPSOIDAL)

        plan.total shouldBe 1
        plan.isClean shouldBe true
    }

    @Test
    fun `S035 RF-19 un punto fuera del ambito del SC nuevo se senala con su motivo y los demas no`() {
        val lambert93 = library(2154, at = GeoPoint(48.0, 2.0)) // ambito de Francia

        val plan = CrsChange.plan(listOf(madrid, paris), lambert93, HeightSystem.ELLIPSOIDAL)

        plan.total shouldBe 2
        plan.issues shouldHaveSize 1
        plan.issues.single().id shouldBe "p1"
        plan.issues.single().unavailable shouldBe setOf(PositionUnavailable.OUTSIDE_CRS_AREA)
    }

    @Test
    fun `S035 RF-19 una rejilla de datum que falta se senala con su nombre`() {
        val ed50 = library(23030)
        val noGrids = object : LibraryResources {
            override val grids = es.topophone.core.geodesy.datum.GridSource { null }
            override val generation = 0

            override fun geoid(gridName: String) = null
        }
        val previous = LibraryResources.current
        LibraryResources.current = noGrids
        try {
            val plan = CrsChange.plan(
                listOf(madrid),
                ed50,
                HeightSystem.ELLIPSOIDAL,
                ProjectPositionCalculator(libraryResources = {
                    noGrids
                }),
            )

            plan.issues shouldHaveSize 1
            plan.issues.single().unavailable shouldBe setOf(PositionUnavailable.MISSING_GRID)
            plan.missingGrids.size shouldBe 1
        } finally {
            LibraryResources.current = previous
        }
    }

    @Test
    fun `S035 RF-19 que un punto no tenga altura no impide el cambio de SC`() {
        val noHeight = PlanPoint("p3", "P3", GeodeticPosition(40.4168, -3.7038, null))

        CrsChange.plan(listOf(noHeight), SpainCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC).isClean shouldBe true
    }

    @Test
    fun `S035 RF-17 un SC geografico calcula los puntos`() {
        CrsChange.plan(listOf(madrid), SpainCrs.ETRS89_GEOGRAPHIC, HeightSystem.ELLIPSOIDAL).isClean shouldBe true
    }

    @Test
    fun `S035 RF-17 ETRS89 de Espana y de la biblioteca son el mismo datum y ED50 no`() {
        CrsChange.sameDatum(SpainCrs.ETRS89_UTM30, library(2154, at = GeoPoint(48.0, 2.0))) shouldBe true
        CrsChange.sameDatum(SpainCrs.ETRS89_UTM30, library(25831)) shouldBe true
        CrsChange.sameDatum(SpainCrs.ETRS89_UTM30, SpainCrs.REGCAN95_UTM28) shouldBe true
        CrsChange.sameDatum(SpainCrs.ETRS89_UTM30, SpainCrs.ED50_UTM30) shouldBe false
        CrsChange.sameDatum(SpainCrs.ETRS89_UTM30, library(23030)) shouldBe false
    }

    @Test
    fun `S035 RF-17 ED50 de Espana y de la biblioteca son el mismo datum`() {
        CrsChange.sameDatum(SpainCrs.ED50_UTM30, library(23030)) shouldBe true
        CrsChange.sameDatum(SpainCrs.ED50_UTM30, library(23031)) shouldBe true
        CrsChange.sameDatum(library(23030), library(25830)) shouldBe false
    }

    @Test
    fun `S035 RF-17 un SC con otro elipsoide o otra transformacion es otro datum`() {
        val greek = library(2100, CorrectionsDatum.ITRF_WGS84, GeoPoint(38.0, 23.5))
        val romanian = library(3844, CorrectionsDatum.ETRS89, GeoPoint(44.43, 26.1))

        CrsChange.sameDatum(greek, SpainCrs.ETRS89_UTM30) shouldBe false
        CrsChange.sameDatum(romanian, SpainCrs.ETRS89_UTM30) shouldBe false
        CrsChange.sameDatum(romanian, romanian.copy(correctionsDatum = CorrectionsDatum.ETRS89)) shouldBe true
    }

    @Test
    fun `S035 RNF un cambio de SC con 10000 puntos se planifica en menos de 2 s`() {
        val points =
            List(10_000) { PlanPoint("p$it", "P$it", GeodeticPosition(40.0 + (it % 100) * 0.001, -3.0 - (it / 100) * 0.001, 600.0)) }
        val target = library(25830)
        CrsChange.plan(points.take(100), target, HeightSystem.ELLIPSOIDAL) // calienta la JVM

        val millis = measureTimeMillis { CrsChange.plan(points, target, HeightSystem.ELLIPSOIDAL).isClean shouldBe true }

        millis shouldBeLessThan 2_000L
    }
}

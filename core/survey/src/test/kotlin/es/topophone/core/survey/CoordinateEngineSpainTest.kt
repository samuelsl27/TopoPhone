package es.topophone.core.survey

import es.topophone.core.geodesy.Ed50
import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.Utm
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.SpainCrs
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El motor de los diez SC de España da bit a bit lo de S008 (S034/RF-25, RF-26). */
class CoordinateEngineSpainTest {
    private val calculator = ProjectPositionCalculator()
    private val points = listOf(
        GeodeticPosition(40.4168, -3.7038, 667.0),
        GeodeticPosition(41.3851, 2.1734, 12.5),
        GeodeticPosition(39.5696, 2.6502, 20.0),
        GeodeticPosition(43.2630, -2.9350, null),
    )

    @Test
    fun `S034 RF-25 el motor da la UTM de S008 sin cambiar un bit en ETRS89`() {
        points.forEach { ground ->
            val position = calculator.engineFor(SpainCrs.ETRS89_UTM30).fromGround(ground, HeightSystem.ELLIPSOIDAL)
            val utm = Utm.forward(ground.latitudeDeg, ground.longitudeDeg, 30, Ellipsoid.GRS80)

            position.easting shouldBe utm.easting
            position.northing shouldBe utm.northing
            position.height shouldBe ground.ellipsoidalHeightM
            position.positionZone shouldBe Utm.zoneFor(ground.longitudeDeg)
        }
    }

    @Test
    fun `S034 RF-25 en ED50 pasa por la rejilla del IGN y el elipsoide internacional como en S008`() {
        val ground = GeodeticPosition(40.4168, -3.7038, 667.0)

        val position = calculator.engineFor(SpainCrs.ED50_UTM30).fromGround(ground, HeightSystem.ORTHOMETRIC)

        val (lat, lon) = Ed50.fromEtrs89(ground.latitudeDeg, ground.longitudeDeg).shouldNotBeNull()
        val utm = Utm.forward(lat, lon, 30, Ellipsoid.INTERNATIONAL_1924)
        position.easting shouldBe utm.easting
        position.northing shouldBe utm.northing
    }

    @Test
    fun `S034 RF-25 el calculador y el motor dan lo mismo y de ida y vuelta el suelo se recupera`() {
        points.forEach { ground ->
            SpainCrs.entries.filter { it.area.contains(ground.latitudeDeg, ground.longitudeDeg) }.forEach { crs ->
                val viaCalculator = calculator.fromGround(ground, crs, HeightSystem.ELLIPSOIDAL)
                val viaEngine = calculator.engineFor(crs).fromGround(ground, HeightSystem.ELLIPSOIDAL)
                viaCalculator shouldBe viaEngine
                if (crs.isUtm && viaEngine.easting != null) {
                    val back = calculator.engineFor(crs).groundFromGrid(viaEngine.easting!!, viaEngine.northing!!)
                        .shouldBeInstanceOf<GroundResult.Ok>()
                    (kotlin.math.abs(back.latitudeDeg - ground.latitudeDeg) < 1e-9) shouldBe true
                    (kotlin.math.abs(back.longitudeDeg - ground.longitudeDeg) < 1e-9) shouldBe true
                }
            }
        }
    }

    @Test
    fun `S034 RF-25 fuera de la rejilla de ED50 no hay coordenadas y un punto fuera del ambito se rechaza`() {
        val engine = calculator.engineFor(SpainCrs.ED50_UTM30)

        engine.groundFromDatum(60.0, -3.0) shouldBe GroundResult.Unavailable(PositionUnavailable.OUTSIDE_ED50_GRID)
        engine.containsPosition(48.85, 2.35) shouldBe false
        engine.fromGround(GeodeticPosition(48.85, 2.35, 0.0), HeightSystem.ELLIPSOIDAL).unavailable shouldBe
            setOf(PositionUnavailable.OUTSIDE_CRS_AREA)
    }

    @Test
    fun `S034 RF-25 la ondulacion es la del geoide del ambito y fuera de el no hay`() {
        val peninsula = calculator.engineFor(SpainCrs.ETRS89_UTM30).undulation(40.4, -3.7)
        val canarias = calculator.engineFor(SpainCrs.REGCAN95_UTM28).undulation(28.1, -15.4)

        peninsula.shouldBeInstanceOf<Undulation.Value>()
        canarias.shouldBeInstanceOf<Undulation.Value>()
        calculator.engineFor(SpainCrs.ETRS89_UTM30).undulation(50.0, -3.0) shouldBe Undulation.Outside
    }
}

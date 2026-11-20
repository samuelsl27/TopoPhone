package es.topophone.core.geodesy.projection

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.PointFactors
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Las longitudes se cuentan desde el meridiano de origen del SC y la API habla en Greenwich (S032/RF-17). */
class PrimeMeridianProjectionTest {

    /** Una proyección de mentira: Este = longitud del SC y Norte = latitud, para ver qué longitud le llega. */
    private class Identity : MapProjection {
        var lastFactorsLongitude = Double.NaN

        override fun forward(latitudeDeg: Double, longitudeDeg: Double) = ProjectedPoint(longitudeDeg, latitudeDeg)

        override fun inverse(eastingM: Double, northingM: Double) = GeoPoint(northingM, eastingM)

        override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors {
            lastFactorsLongitude = longitudeDeg
            return PointFactors(1.0, 0.0)
        }
    }

    private val madridDeg = -3.687938

    @Test
    fun `S032 RF-17 con Greenwich el envoltorio no cambia nada`() {
        val projection = PrimeMeridianProjection(Identity(), 0.0)

        projection.forward(40.0, -3.0) shouldBe ProjectedPoint(-3.0, 40.0)
        projection.inverse(-3.0, 40.0) shouldBe GeoPoint(40.0, -3.0)
    }

    @Test
    fun `S032 RF-17 al proyectar se resta el desplazamiento a la longitud`() {
        val projection = PrimeMeridianProjection(Identity(), madridDeg)

        // Greenwich 0° es 3,687938° al este de Madrid.
        projection.forward(40.0, 0.0)!!.eastingM shouldBe (3.687938).plusOrMinus(1e-12)
        // El propio meridiano de Madrid es el 0° del SC.
        projection.forward(40.0, madridDeg)!!.eastingM shouldBe 0.0.plusOrMinus(1e-12)
    }

    @Test
    fun `S032 RF-17 al desproyectar se suma el desplazamiento a la longitud`() {
        val projection = PrimeMeridianProjection(Identity(), madridDeg)

        projection.inverse(3.687938, 40.0)!!.longitudeDeg shouldBe 0.0.plusOrMinus(1e-12)
        projection.inverse(0.0, 40.0)!!.longitudeDeg shouldBe madridDeg.plusOrMinus(1e-12)
    }

    @Test
    fun `S032 RF-17 ida y vuelta devuelven la longitud de partida con Madrid, Paris y Lisboa`() {
        listOf(-3.687938, 2.337229, -9.131907).forEach { prime ->
            val projection = PrimeMeridianProjection(Identity(), prime)
            val point = projection.forward(41.5, -7.25)!!

            projection.inverse(point.eastingM, point.northingM)!!.longitudeDeg shouldBe (-7.25).plusOrMinus(1e-12)
        }
    }

    @Test
    fun `S032 RF-17 un punto que con el desplazamiento cruza el antimeridiano se normaliza`() {
        val projection = PrimeMeridianProjection(Identity(), 170.0)

        // 175° desde Greenwich son 5° desde un meridiano en 170°; -175° son 15° (no -345°).
        projection.forward(0.0, 175.0)!!.eastingM shouldBe 5.0.plusOrMinus(1e-12)
        projection.forward(0.0, -175.0)!!.eastingM shouldBe 15.0.plusOrMinus(1e-12)
        // Y al volver, 15° desde ese meridiano es 185° = -175° desde Greenwich.
        projection.inverse(15.0, 0.0)!!.longitudeDeg shouldBe (-175.0).plusOrMinus(1e-12)
    }

    @Test
    fun `S032 RF-17 los factores se calculan con la longitud del SC`() {
        val inner = Identity()
        val projection = PrimeMeridianProjection(inner, madridDeg)

        projection.factors(40.0, 0.0)

        inner.lastFactorsLongitude shouldBe 3.687938.plusOrMinus(1e-12)
    }

    @Test
    fun `S032 RF-17 si el metodo no tiene imagen el envoltorio tampoco inventa una`() {
        val none = object : MapProjection {
            override fun forward(latitudeDeg: Double, longitudeDeg: Double): ProjectedPoint? = null

            override fun inverse(eastingM: Double, northingM: Double): GeoPoint? = null

            override fun factors(latitudeDeg: Double, longitudeDeg: Double): PointFactors? = null
        }
        val projection = PrimeMeridianProjection(none, madridDeg)

        projection.forward(0.0, 0.0) shouldBe null
        projection.inverse(0.0, 0.0) shouldBe null
        projection.factors(0.0, 0.0) shouldBe null
    }
}

package es.topophone.app.ui.surface

import io.kotest.matchers.shouldBe
import org.junit.Test
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Polygon

/** El GeoJSON de la malla coloreada y del contorno con la activa marcada (S061/RF-7, RF-10, RF-13, plan A5). */
class SurfaceGeoJsonTest {

    @Test
    fun `S061 RF-7 RF-10 cada triangulo es un poligono cerrado con su color`() {
        val mesh = SurfaceMeshLayer(
            doubleArrayOf(-3.70, 40.41, -3.69, 40.41, -3.69, 40.42, -3.70, 40.41, -3.69, 40.42, -3.70, 40.42),
            intArrayOf(0xFF1A237E.toInt(), 0xFFE53935.toInt()),
        )

        val features = SurfaceGeoJson.mesh(listOf(mesh)).features()!!

        features.size shouldBe 2
        val ring = (features[0].geometry() as Polygon).coordinates()[0]
        ring.size shouldBe 4
        ring.first() shouldBe ring.last()
        ring[1].longitude() shouldBe -3.69
        features.map { it.getStringProperty(SurfaceGeoJson.COLOR) } shouldBe listOf("#1A237E", "#E53935")
    }

    @Test
    fun `S061 RF-13 el contorno se cierra y la activa va marcada`() {
        val outlines = listOf(
            SurfaceOutlineLine(doubleArrayOf(-3.70, 40.41, -3.69, 40.41, -3.69, 40.42), active = true),
            SurfaceOutlineLine(doubleArrayOf(-3.60, 40.41, -3.59, 40.41, -3.59, 40.42, -3.60, 40.42), active = false),
        )

        val features = SurfaceGeoJson.outlines(outlines).features()!!

        features.map { (it.geometry() as LineString).coordinates().size } shouldBe listOf(4, 5)
        (features[0].geometry() as LineString).coordinates().let { it.first() shouldBe it.last() }
        features.map { it.getBooleanProperty(SurfaceGeoJson.ACTIVE) } shouldBe listOf(true, false)
    }

    @Test
    fun `S061 RF-6 sin nada que dibujar las fuentes quedan vacias`() {
        SurfaceGeoJson.mesh(emptyList()).features()!!.size shouldBe 0
        SurfaceGeoJson.outlines(emptyList()).features()!!.size shouldBe 0
    }
}

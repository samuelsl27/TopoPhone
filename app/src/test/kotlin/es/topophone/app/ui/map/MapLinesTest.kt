package es.topophone.app.ui.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.codes.PointCode
import es.topophone.core.model.line.LineVertex
import es.topophone.core.model.line.ProjectLine
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.point.PointSummary
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Las líneas del proyecto en el mapa: GeoJSON con el color de su código, sin las ocultas ni las incompletas (S025/RF-22, RF-23). */
@RunWith(AndroidJUnit4::class)
class MapLinesTest {

    private fun point(id: String, lon: Double, lat: Double) = PointSummary(
        id = id,
        name = id,
        description = "",
        origin = PointOrigin.MANUAL,
        latitudeDeg = lat,
        longitudeDeg = lon,
        hasHeight = false,
        outOfTolerance = false,
        edited = false,
        hidden = false,
        createdAtMs = 0,
    )

    private val points = listOf(point("a", -3.70, 40.41), point("b", -3.69, 40.42), point("c", -3.68, 40.41), point("d", -3.67, 40.40))
    private val bord = PointCode("BORD", "", 0x1E88E5, CodeSymbol.CIRCLE)

    private fun line(
        id: String,
        name: String = id,
        closed: Boolean = false,
        hidden: Boolean = false,
        active: Boolean = false,
        code: PointCode? = bord,
        vararg vertices: String,
    ) = ProjectLine(
        id = id,
        projectId = "p1",
        name = name,
        code = code,
        geometry = if (closed) CodeGeometry.POLYGON else CodeGeometry.LINE,
        closed = closed,
        hidden = hidden,
        active = active,
        vertices = vertices.map { LineVertex(it) },
        createdAtMs = 0,
    )

    private fun features(scene: LineScene) = JSONObject(scene.geoJson).getJSONArray("features")

    @Test
    fun `S025 RF-22 una linea abierta es una LineString con longitud y latitud de sus puntos en orden`() {
        val scene = MapLines.scene(listOf(line("l1", "BORD_1", vertices = arrayOf("c", "a", "b"))), points).shouldNotBeNull()

        scene.lines shouldBe 1
        scene.polygons shouldBe 0
        val feature = features(scene).getJSONObject(0)
        feature.getJSONObject("geometry").getString("type") shouldBe "LineString"
        val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
        coordinates.length() shouldBe 3
        coordinates.getJSONArray(0).getDouble(0) shouldBe -3.68
        coordinates.getJSONArray(0).getDouble(1) shouldBe 40.41
        coordinates.getJSONArray(2).getDouble(0) shouldBe -3.69
    }

    @Test
    fun `S025 RF-22 un poligono es un Polygon con el anillo cerrado y su color y nombre`() {
        val scene = MapLines.scene(listOf(line("l2", "PARC_1", closed = true, vertices = arrayOf("a", "b", "c"))), points).shouldNotBeNull()

        scene.polygons shouldBe 1
        val feature = features(scene).getJSONObject(0)
        feature.getJSONObject("geometry").getString("type") shouldBe "Polygon"
        val ring = feature.getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(0)
        ring.length() shouldBe 4
        ring.getJSONArray(0).toString() shouldBe ring.getJSONArray(3).toString()
        feature.getJSONObject("properties").getString("color") shouldBe "#1E88E5"
        feature.getJSONObject("properties").getString("name") shouldBe "PARC_1"
        feature.getJSONObject("properties").getBoolean("polygon") shouldBe true
    }

    @Test
    fun `S025 RF-22 la linea activa se marca y una sin codigo usa el color por defecto`() {
        val scene = MapLines.scene(listOf(line("l1", active = true, code = null, vertices = arrayOf("a", "b"))), points).shouldNotBeNull()

        val properties = features(scene).getJSONObject(0).getJSONObject("properties")
        properties.getBoolean("active") shouldBe true
        properties.getString("color") shouldBe "#37474F"
    }

    @Test
    fun `S025 RF-23 las lineas ocultas no se dibujan`() {
        MapLines.scene(listOf(line("l1", hidden = true, vertices = arrayOf("a", "b"))), points).shouldBeNull()

        val scene = MapLines.scene(
            listOf(line("l1", hidden = true, vertices = arrayOf("a", "b")), line("l2", vertices = arrayOf("a", "b"))),
            points,
        ).shouldNotBeNull()
        features(scene).length() shouldBe 1
        features(scene).getJSONObject(0).getJSONObject("properties").getString("id") shouldBe "l2"
    }

    @Test
    fun `S025 RF-22 una linea con menos de 2 vertices con posicion, o un poligono con menos de 3, no se dibuja`() {
        val scene = MapLines.scene(
            listOf(
                line("corta", vertices = arrayOf("a", "falta")),
                line("triangulo", closed = true, vertices = arrayOf("a", "b")),
                line("bien", vertices = arrayOf("a", "b", "falta")),
            ),
            points,
        ).shouldNotBeNull()

        features(scene).length() shouldBe 1
        features(scene).getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").length() shouldBe 2
    }

    @Test
    fun `S025 RF-17 si un punto cambia de sitio la linea sale con la posicion nueva`() {
        val moved = points.map { if (it.id == "b") it.copy(longitudeDeg = -3.5) else it }

        val scene = MapLines.scene(listOf(line("l1", vertices = arrayOf("a", "b"))), moved).shouldNotBeNull()

        features(scene).getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates").getJSONArray(1).getDouble(0) shouldBe -3.5
    }

    @Test
    fun `S025 RF-22 los nombres con comillas y barras salen escapados`() {
        val scene = MapLines.scene(
            listOf(line("l1", name = "Acera \"norte\" \\ 2", vertices = arrayOf("a", "b"))),
            points,
        ).shouldNotBeNull()

        features(scene).getJSONObject(0).getJSONObject("properties").getString("name") shouldBe "Acera \"norte\" \\ 2"
        scene.geoJson shouldContain "\\\"norte\\\""
    }

    @Test
    fun `S025 RF-22 sin lineas no hay escena`() {
        MapLines.scene(emptyList(), points).shouldBeNull()
    }
}

package es.topophone.app.ui.cad

import es.topophone.app.ui.map.MapCamera
import es.topophone.core.model.cad.CadColor
import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.DesignLayer
import es.topophone.core.model.cad.DesignLayerKind
import es.topophone.core.model.project.ProjectCrs
import es.topophone.data.cad.CadChunkContent
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Lo que cae en la vista, la simplificación por zoom y el color de contraste (S040/RF-24, RF-25, RNF-T13). */
class DesignSceneBuilderTest {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val crs = ProjectCrs.ETRS89_UTM30

    private fun layer(id: String, kind: DesignLayerKind, color: CadColor = CadColor.rgb(0xFF0000)) =
        DesignLayer(id, "d", "Capa $id", kind, color)

    private fun chunk(layerId: String, vararg entities: CadEntity) =
        CadChunkContent(1L, layerId, CadExtent(0.0, 1.0, 0.0, 1.0), entities.toList())

    private fun line(vararg en: Double, color: CadColor? = null): CadEntity {
        val e = DoubleArray(en.size / 2) { en[it * 2] }
        val n = DoubleArray(en.size / 2) { en[it * 2 + 1] }
        return CadEntity(CadEntityKind.LINE, e, n, null, color)
    }

    private fun point(e: Double, n: Double, kind: CadEntityKind = CadEntityKind.POINT, color: CadColor? = null) =
        CadEntity(kind, doubleArrayOf(e), doubleArrayOf(n), null, color)

    private fun build(layers: List<DesignLayer>, chunks: List<CadChunkContent>, mpp: Double = 1.0, contrast: Int = black) =
        DesignSceneBuilder.build(crs, layers, chunks, mpp, contrast)

    @Test
    fun `S040 RF-24 un punto del dibujo va al mapa en sus geograficas de pyproj`() {
        // Madrid: (440297,9875; 4474244,0011) en ETRS89 UTM 30 son 40,41668 N y 3,70371 O.
        val scene = build(listOf(layer("p", DesignLayerKind.POINTS)), listOf(chunk("p", point(440_297.9875, 4_474_244.0011))))

        scene.points.single().latitudeDeg shouldBe (40.41668 plusOrMinus 1e-8)
        scene.points.single().longitudeDeg shouldBe (-3.70371 plusOrMinus 1e-8)
        scene.points.single().argb shouldBe 0xFFFF0000.toInt()
    }

    @Test
    fun `S040 RF-24 el punto de insercion de un bloque se dibuja como un punto`() {
        val scene = build(
            listOf(layer("p", DesignLayerKind.POINTS)),
            listOf(chunk("p", point(440_000.0, 4_474_000.0, CadEntityKind.BLOCK_INSERT))),
        )

        scene.points.size shouldBe 1
        scene.lines.size shouldBe 0
    }

    @Test
    fun `S040 RF-24 una linea lleva sus vertices en longitud y latitud y el color de su capa`() {
        val scene = build(
            listOf(layer("l", DesignLayerKind.LINES, CadColor.rgb(0x00FF00))),
            listOf(chunk("l", line(440_000.0, 4_474_000.0, 440_100.0, 4_474_050.0))),
        )

        val drawn = scene.lines.single()
        drawn.argb shouldBe 0xFF00FF00.toInt()
        drawn.vertexCount shouldBe 2
        (drawn.lonLat[0] in -3.8..-3.6) shouldBe true // longitud
        (drawn.lonLat[1] in 40.3..40.5) shouldBe true // latitud
    }

    @Test
    fun `S040 RF-24 el color propio de un elemento manda sobre el de su capa`() {
        val scene = build(
            listOf(layer("l", DesignLayerKind.LINES)),
            listOf(chunk("l", line(0.0, 0.0, 1.0, 1.0, color = CadColor.rgb(0x0000FF)).let { moved(it) })),
        )

        scene.lines.single().argb shouldBe 0xFF0000FF.toInt()
    }

    private fun moved(e: CadEntity) = CadEntity(
        e.kind,
        e.easting.map {
            it + 440_000.0
        }.toDoubleArray(),
        e.northing.map { it + 4_474_000.0 }.toDoubleArray(),
        null,
        e.color,
    )

    @Test
    fun `S040 RF-25 el blanco y el negro del DXF se pintan del color de contraste del mapa`() {
        val layers =
            listOf(layer("l", DesignLayerKind.LINES, CadColor.AUTO_CONTRAST), layer("p", DesignLayerKind.POINTS, CadColor.AUTO_CONTRAST))
        val chunks = listOf(
            chunk("l", moved(line(0.0, 0.0, 1.0, 1.0))),
            chunk("p", point(440_000.0, 4_474_000.0)),
        )

        build(layers, chunks, contrast = black).let {
            it.lines.single().argb shouldBe black
            it.points.single().argb shouldBe black
        }
        build(layers, chunks, contrast = white).let {
            it.lines.single().argb shouldBe white
            it.points.single().argb shouldBe white
        }
    }

    @Test
    fun `S040 RF-27 una capa que no esta entre las visibles no se dibuja`() {
        val scene = build(listOf(layer("a", DesignLayerKind.LINES)), listOf(chunk("oculta", moved(line(0.0, 0.0, 5.0, 5.0)))))

        scene.isEmpty shouldBe true
    }

    @Test
    fun `S040 RNF-T13 un elemento de un solo vertice, como un circulo minimo, no se dibuja como linea`() {
        val tiny = CadEntity(CadEntityKind.CIRCLE, doubleArrayOf(440_000.0), doubleArrayOf(4_474_000.0), null, null)

        build(listOf(layer("l", DesignLayerKind.LINES)), listOf(chunk("l", tiny))).isEmpty shouldBe true
    }

    @Test
    fun `S040 RNF-T13 los vertices a menos de un pixel del anterior se quitan y los extremos se quedan`() {
        val n = 1_000
        val e = DoubleArray(n) { 440_000.0 + it * 0.1 } // cada 10 cm
        val line = CadEntity(CadEntityKind.POLYLINE, e, DoubleArray(n) { 4_474_000.0 }, null, null)

        val scene = build(listOf(layer("l", DesignLayerKind.LINES)), listOf(chunk("l", line)), mpp = 1.0)

        val kept = scene.lines.single().vertexCount
        (kept in 90..110) shouldBe true // un vértice por metro: unos 100
        scene.lines.single().lonLat.take(2).let { (it[0] < scene.lines.single().lonLat[2]) shouldBe true }
    }

    @Test
    fun `S040 RNF-T13 con un zoom mas cerca no se quita ningun vertice`() {
        val n = 200
        val line = CadEntity(CadEntityKind.POLYLINE, DoubleArray(n) { 440_000.0 + it }, DoubleArray(n) { 4_474_000.0 }, null, null)

        build(listOf(layer("l", DesignLayerKind.LINES)), listOf(chunk("l", line)), mpp = 0.01).lines.single().vertexCount shouldBe n
    }

    @Test
    fun `S040 RNF-T13 una escena enorme se simplifica hasta caber en el limite`() {
        val n = 200_000
        // Un trazado continuo de 2 km con un vértice cada centímetro: pasa del limite hasta que el umbral sube a unos 7 cm.
        val line = CadEntity(
            CadEntityKind.POLYLINE,
            DoubleArray(n) { 440_000.0 + it * 0.01 },
            DoubleArray(n) { 4_474_000.0 + Math.sin(it / 5_000.0) * 50 },
            null,
            null,
        )

        val scene = build(listOf(layer("l", DesignLayerKind.LINES)), listOf(chunk("l", line)), mpp = 0.01)

        (scene.vertexCount <= DesignSceneBuilder.MAX_VERTICES) shouldBe true
        scene.lines.single().vertexCount shouldBe scene.lines.single().vertexCount.coerceAtLeast(2)
    }

    @Test
    fun `S040 RNF-T13 muchos puntos se aclaran para no pasar del limite`() {
        val many = (0 until 100_000).map { point(440_000.0 + it % 1000, 4_474_000.0 + it / 1000) }

        val scene = build(listOf(layer("p", DesignLayerKind.POINTS)), listOf(chunk("p", *many.toTypedArray())))

        (scene.points.size <= DesignSceneBuilder.MAX_VERTICES) shouldBe true
        (scene.points.size > 30_000) shouldBe true
    }

    @Test
    fun `S040 RF-24 la vista que se carga es 1,5 veces la pantalla alrededor del centro de la camara`() {
        val camera = MapCamera(40.41668, -3.70371, 17.0)

        val box = DesignSceneBuilder.viewport(camera, crs)!!

        val half = (box.maxEasting - box.minEasting) / 2
        // Zoom 17 en esa latitud son unos 0,46 m por píxel; 1.200 px por 1,5 son unos 820 m a cada lado.
        (half in 700.0..950.0) shouldBe true
        ((box.minEasting + box.maxEasting) / 2 shouldBe (440_297.99 plusOrMinus 0.1))
        ((box.minNorthing + box.maxNorthing) / 2 shouldBe (4_474_244.0 plusOrMinus 0.1))
    }

    @Test
    fun `S040 RF-24 con la camara fuera del ambito del sistema no hay vista que cargar`() {
        DesignSceneBuilder.viewport(MapCamera(0.0, 0.0, 10.0), crs).shouldBeNull()
        DesignSceneBuilder.viewport(MapCamera(28.3, -16.2, 12.0), ProjectCrs.ETRS89_UTM30).shouldBeNull()
    }

    @Test
    fun `S040 RF-24 en un proyecto en geograficas la vista se da en grados`() {
        val box = DesignSceneBuilder.viewport(MapCamera(40.41668, -3.70371, 17.0), ProjectCrs.ETRS89_GEOGRAPHIC)!!

        ((box.minEasting + box.maxEasting) / 2 shouldBe (-3.70371 plusOrMinus 1e-9))
        ((box.maxNorthing - box.minNorthing) / 2 shouldBe (0.0074 plusOrMinus 0.002)) // unos 820 m en grados de latitud
    }
}

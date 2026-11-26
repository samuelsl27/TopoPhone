package es.topophone.app.ui.surface

import es.topophone.core.model.surface.ElevationRamp
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Un anillo del contorno de una superficie en longitud y latitud, cerrado implícitamente; [active] lo dibuja más grueso (S061/RF-6, RF-13). */
class SurfaceOutlineLine(val lonLat: DoubleArray, val active: Boolean) {
    val vertexCount: Int get() = lonLat.size / 2
}

/** Triángulos de la malla de una superficie: tres vértices (longitud, latitud) por triángulo y su color ARGB (S061/RF-7, RF-10). */
class SurfaceMeshLayer(val lonLat: DoubleArray, val argb: IntArray) {
    init {
        require(lonLat.size == argb.size * 6) { "Tres vértices por triángulo" }
    }

    val count: Int get() = argb.size
}

/**
 * La imagen de las celdas de un ráster en la vista (S061/RF-9): [width] × [height] píxeles ARGB fila a fila, transparentes sin dato, y
 * las cuatro esquinas en longitud y latitud: arriba a la izquierda, arriba a la derecha, abajo a la derecha y abajo a la izquierda.
 */
class SurfaceRasterImage(val surfaceId: String, val width: Int, val height: Int, val argb: IntArray, val cornersLonLat: DoubleArray) {
    init {
        require(argb.size == width * height && cornersLonLat.size == 8) { "Una imagen con su tamaño y sus cuatro esquinas" }
    }
}

/**
 * Lo que el mapa dibuja de las superficies visibles en la vista de ahora (S061/RF-6 a RF-13): los contornos de todas, la malla de las
 * TIN con pocos triángulos en la vista y la imagen de los rásteres con pocas celdas; [ramp] es la rampa común que da los colores y la
 * leyenda (RF-10, RF-11), o null si no hay nada coloreado.
 */
class SurfaceScene(
    val outlines: List<SurfaceOutlineLine>,
    val meshes: List<SurfaceMeshLayer>,
    val images: List<SurfaceRasterImage>,
    val ramp: ElevationRamp?,
) {
    val isEmpty: Boolean get() = outlines.isEmpty() && meshes.isEmpty() && images.isEmpty()

    /** Hay algo coloreado por cota: la malla de un TIN o la imagen de un ráster (RF-11). */
    val colored: Boolean get() = meshes.any { it.count > 0 } || images.isNotEmpty()
}

/** Las capas de las superficies en el mapa (S061/RF-12). Las imágenes de los rásteres van aparte, debajo de todas. */
enum class SurfaceLayerKind { FILL, EDGES, OUTLINE }

/** El GeoJSON de la malla y del contorno de una [SurfaceScene] para las fuentes del mapa (S061, plan A5). */
object SurfaceGeoJson {
    /** Propiedad con el color `#RRGGBB` de un triángulo. */
    const val COLOR = "c"

    /** Propiedad que marca el contorno de la superficie activa. */
    const val ACTIVE = "active"

    /**
     * El orden de las capas, de abajo arriba: el relleno, las aristas encima y el contorno encima de todo. Todas van justo encima
     * del ancla de las capas WMS, y por tanto encima del mapa base y de las WMS y debajo de líneas, dibujos y puntos (S061/RF-12).
     */
    val LAYERS_BOTTOM_UP = listOf(SurfaceLayerKind.FILL, SurfaceLayerKind.EDGES, SurfaceLayerKind.OUTLINE)

    fun mesh(meshes: List<SurfaceMeshLayer>): FeatureCollection = FeatureCollection.fromFeatures(
        meshes.flatMap { mesh ->
            List(mesh.count) { t ->
                val base = t * 6
                val ring = listOf(
                    Point.fromLngLat(mesh.lonLat[base], mesh.lonLat[base + 1]),
                    Point.fromLngLat(mesh.lonLat[base + 2], mesh.lonLat[base + 3]),
                    Point.fromLngLat(mesh.lonLat[base + 4], mesh.lonLat[base + 5]),
                    Point.fromLngLat(mesh.lonLat[base], mesh.lonLat[base + 1]),
                )
                Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply { addStringProperty(COLOR, hex(mesh.argb[t])) }
            }
        },
    )

    fun outlines(lines: List<SurfaceOutlineLine>): FeatureCollection = FeatureCollection.fromFeatures(
        lines.filter { it.vertexCount >= 2 }.map { line ->
            val points = List(line.vertexCount + 1) { i ->
                val k = i % line.vertexCount
                Point.fromLngLat(line.lonLat[2 * k], line.lonLat[2 * k + 1])
            }
            Feature.fromGeometry(LineString.fromLngLats(points)).apply { addBooleanProperty(ACTIVE, line.active) }
        },
    )

    fun hex(argb: Int): String = String.format(java.util.Locale.ROOT, "#%06X", argb and 0xFFFFFF)
}

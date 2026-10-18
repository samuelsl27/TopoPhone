package es.topophone.app.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import es.topophone.core.geodesy.AccuracyCircle
import es.topophone.core.geodesy.MapGrid
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.ScaleBar
import es.topophone.core.model.project.LinearUnit
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** [MapController] sobre MapLibre (ADR-9). */
internal class MapLibreMapController(private val map: MapLibreMap, private val mapView: MapView, private val client: OkHttpClient) :
    MapController {
    private var settings: MapSettings? = null
    private var unit = LinearUnit.METRE
    private val handler = Handler(Looper.getMainLooper())
    private var cadastreCall: Call? = null
    private val requestCadastre = Runnable { loadCadastre() }
    private var rover: RoverMarker? = null
    private val cameraFlow = MutableStateFlow<MapCamera?>(null)
    override val camera: StateFlow<MapCamera?> = cameraFlow

    init {
        map.uiSettings.isRotateGesturesEnabled = MapOptions.ROTATE_GESTURES // S009/RF-11
        map.uiSettings.isTiltGesturesEnabled = MapOptions.TILT_GESTURES
        map.uiSettings.isLogoEnabled = MapOptions.MAPLIBRE_ATTRIBUTION // S009/RF-9
        map.uiSettings.isAttributionEnabled = MapOptions.MAPLIBRE_ATTRIBUTION
        map.setMaxZoomPreference(MapOptions.MAX_ZOOM) // S009/RF-12
        map.addOnCameraMoveListener { publishCamera() }
        map.addOnCameraIdleListener {
            publishCamera()
            updateGrid()
            scheduleCadastre(CADASTRE_DELAY_MS)
        }
        publishCamera()
    }

    private fun publishCamera() {
        val position = map.cameraPosition
        val target = position.target ?: return
        cameraFlow.value = MapCamera(target.latitude, target.longitude, position.zoom)
    }

    override fun setSettings(settings: MapSettings) {
        val previous = this.settings
        if (settings == previous) return
        this.settings = settings
        if (settings.basemap != previous?.basemap) {
            val builder = when (val style = MapStyles.style(settings.basemap)) {
                is BasemapStyle.Remote -> Style.Builder().fromUri(style.url)
                is BasemapStyle.Local -> Style.Builder().fromJson(style.json)
            }
            map.setStyle(builder) { style ->
                addBackground(style)
                addRoverLayers(style)
                drawRover()
                updateGrid()
                scheduleCadastre(0)
            }
        } else if (settings.cadastre) {
            scheduleCadastre(0)
        } else {
            removeCadastre()
        }
    }

    override fun showRover(marker: RoverMarker?) {
        rover = marker
        drawRover()
    }

    /** Círculo de precisión y rover, encima de todo (S009/RF-16, RF-17). */
    private fun addRoverLayers(style: Style) {
        style.addSource(GeoJsonSource(ACCURACY_SOURCE))
        style.addSource(GeoJsonSource(ROVER_SOURCE))
        style.addLayer(FillLayer(ACCURACY_FILL_LAYER, ACCURACY_SOURCE).withProperties(PropertyFactory.fillOpacity(ACCURACY_OPACITY)))
        style.addLayer(LineLayer(ACCURACY_LINE_LAYER, ACCURACY_SOURCE).withProperties(PropertyFactory.lineWidth(1.5f)))
        style.addLayer(
            CircleLayer(ROVER_LAYER, ROVER_SOURCE).withProperties(
                PropertyFactory.circleRadius(ROVER_RADIUS_DP),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }

    private fun drawRover() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val roverSource = style.getSourceAs<GeoJsonSource>(ROVER_SOURCE) ?: return
        val accuracySource = style.getSourceAs<GeoJsonSource>(ACCURACY_SOURCE) ?: return
        val marker = rover
        if (marker == null) {
            // S009/RF-18: sin posición reciente no se pinta el rover.
            roverSource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            accuracySource.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        roverSource.setGeoJson(Feature.fromGeometry(Point.fromLngLat(marker.position.longitudeDeg, marker.position.latitudeDeg)))
        val circle = marker.accuracyM?.let { radius ->
            val ring = AccuracyCircle.polygon(marker.position, radius).map { Point.fromLngLat(it.longitudeDeg, it.latitudeDeg) }
            Feature.fromGeometry(Polygon.fromLngLats(listOf(ring + ring.first())))
        }
        accuracySource.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(circle)))
        style.getLayer(ROVER_LAYER)?.setProperties(PropertyFactory.circleColor(marker.colorArgb))
        style.getLayer(ACCURACY_FILL_LAYER)?.setProperties(PropertyFactory.fillColor(marker.colorArgb))
        style.getLayer(ACCURACY_LINE_LAYER)?.setProperties(PropertyFactory.lineColor(marker.colorArgb))
    }

    override fun setLinearUnit(unit: LinearUnit) {
        if (unit == this.unit) return
        this.unit = unit
        updateGrid()
    }

    /**
     * S009/RF-14: fondo neutro con cuadrícula debajo de todo, en lugar del fondo del estilo, para que se vea
     * donde falta una tesela.
     */
    private fun addBackground(style: Style) {
        style.layers.filterIsInstance<BackgroundLayer>().forEach { style.removeLayer(it) }
        val bottom = style.layers.firstOrNull()?.id
        val background = BackgroundLayer(BACKGROUND_LAYER).withProperties(PropertyFactory.backgroundColor(MapStyles.BACKGROUND_COLOR))
        val grid = LineLayer(GRID_LAYER, GRID_SOURCE).withProperties(
            PropertyFactory.lineColor(MapStyles.GRID_COLOR),
            PropertyFactory.lineWidth(1f),
        )
        style.addSource(GeoJsonSource(GRID_SOURCE))
        if (bottom == null) {
            style.addLayer(background)
            style.addLayer(grid)
        } else {
            style.addLayerBelow(background, bottom)
            style.addLayerBelow(grid, bottom)
        }
    }

    /** Cuadrícula de la zona visible, cada longitud de la barra de escala (S009/RF-14). */
    private fun updateGrid() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<GeoJsonSource>(GRID_SOURCE) ?: return
        val target = map.cameraPosition.target ?: return
        val bounds = map.projection.visibleRegion.latLngBounds
        val metresPerPixel = WebMercator.metresPerPixel(target.latitude, map.cameraPosition.zoom)
        val bar = ScaleBar.choose(metresPerPixel, SCALE_BAR_MAX_DP, unit)
        val lines = MapGrid.lines(
            bounds.latitudeSouth,
            bounds.longitudeWest,
            bounds.latitudeNorth,
            bounds.longitudeEast,
            bar.value * unit.metres,
        )
        val features = lines.map { line ->
            Feature.fromGeometry(LineString.fromLngLats(line.map { Point.fromLngLat(it.longitudeDeg, it.latitudeDeg) }))
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** S009/RF-7: el Catastro se pide cuando el mapa se detiene, y solo la última petición cuenta. */
    private fun scheduleCadastre(delayMs: Long) {
        handler.removeCallbacks(requestCadastre)
        if (settings?.cadastre == true) handler.postDelayed(requestCadastre, delayMs)
    }

    private fun loadCadastre() {
        cadastreCall?.cancel()
        if (map.style?.isFullyLoaded != true || mapView.width <= 0 || mapView.height <= 0) return
        val bounds = map.projection.visibleRegion.latLngBounds
        val url = CadastreRequest.url(
            bounds.latitudeSouth,
            bounds.longitudeWest,
            bounds.latitudeNorth,
            bounds.longitudeEast,
            mapView.width,
            mapView.height,
        )
        // El mapa no gira ni se inclina: el encuadre es un rectángulo norte-sur.
        val quad = LatLngQuad(
            LatLng(bounds.latitudeNorth, bounds.longitudeWest),
            LatLng(bounds.latitudeNorth, bounds.longitudeEast),
            LatLng(bounds.latitudeSouth, bounds.longitudeEast),
            LatLng(bounds.latitudeSouth, bounds.longitudeWest),
        )
        val call = client.newCall(Request.Builder().url(url).build())
        cadastreCall = call
        call.enqueue(
            object : Callback {
                // Sin red no hay Catastro; el aviso lo da el mapa (S009/RF-15).
                override fun onFailure(call: Call, e: IOException) = Unit

                override fun onResponse(call: Call, response: Response) {
                    val bitmap = response.use { if (it.isSuccessful) it.body?.byteStream()?.let(BitmapFactory::decodeStream) else null }
                    if (bitmap != null) handler.post { if (cadastreCall === call && settings?.cadastre == true) showCadastre(quad, bitmap) }
                }
            },
        )
    }

    private fun showCadastre(quad: LatLngQuad, bitmap: Bitmap) {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<ImageSource>(CADASTRE_SOURCE)
        if (source == null) {
            style.addSource(ImageSource(CADASTRE_SOURCE, quad, bitmap))
            // Debajo del rover y su círculo.
            style.addLayerBelow(RasterLayer(CADASTRE_LAYER, CADASTRE_SOURCE), ACCURACY_FILL_LAYER)
        } else {
            source.setCoordinates(quad)
            source.setImage(bitmap)
        }
    }

    private fun removeCadastre() {
        handler.removeCallbacks(requestCadastre)
        cadastreCall?.cancel()
        cadastreCall = null
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        style.removeLayer(CADASTRE_LAYER)
        style.removeSource(CADASTRE_SOURCE)
    }

    private companion object {
        const val BACKGROUND_LAYER = "topophone-background"
        const val GRID_SOURCE = "topophone-grid"
        const val GRID_LAYER = "topophone-grid"
        const val CADASTRE_SOURCE = "topophone-cadastre"
        const val CADASTRE_LAYER = "topophone-cadastre"
        const val ACCURACY_SOURCE = "topophone-accuracy"
        const val ACCURACY_FILL_LAYER = "topophone-accuracy-fill"
        const val ACCURACY_LINE_LAYER = "topophone-accuracy-line"
        const val ROVER_SOURCE = "topophone-rover"
        const val ROVER_LAYER = "topophone-rover"
        const val ROVER_RADIUS_DP = 8f
        const val ACCURACY_OPACITY = 0.25f

        /** Espera tras detenerse el mapa antes de pedir el Catastro. */
        const val CADASTRE_DELAY_MS = 500L
    }
}

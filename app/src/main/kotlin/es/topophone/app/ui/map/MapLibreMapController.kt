package es.topophone.app.ui.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import es.topophone.app.ui.alignment.AlignmentScene
import es.topophone.app.ui.cad.DesignScene
import es.topophone.app.ui.surface.SurfaceGeoJson
import es.topophone.app.ui.surface.SurfaceLayerKind
import es.topophone.app.ui.surface.SurfaceScene
import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.geodesy.AccuracyCircle
import es.topophone.core.geodesy.GeoBox
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.MapGrid
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.alignment.slope.SlopeKind
import es.topophone.core.model.codes.CodeSymbol
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.MapViewport
import es.topophone.core.model.map.ScaleBar
import es.topophone.core.model.project.LinearUnit
import java.io.IOException
import kotlin.math.roundToInt
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
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
    private var base: GeoPoint? = null
    private var central: GeoPoint? = null
    private var centralLabel = ""
    private var points: List<MapPoint> = emptyList()
    private var lineScene: LineScene? = null
    private var wmsPlan = WmsLayerPlan.EMPTY
    private var appliedWms = WmsLayerPlan.EMPTY
    private val wmsImages =
        WmsOneImageLoader<Bitmap>(client, HandlerWmsScheduler(handler), { BitmapFactory.decodeStream(it) }) { showWmsImage(it) }
    private var design: DesignScene? = null
    private var surfaceScene: SurfaceScene? = null

    /** Las superficies con imagen (rásteres) que tiene el estilo de ahora; un estilo nuevo no trae ninguna. */
    private val surfaceImageIds = mutableSetOf<String>()
    private var alignmentScene: AlignmentScene? = null
    private var stakeout: StakeoutMarker? = null
    private var bottomInsetPx = 0
    private var appliedInsetPx = 0
    private var easing = false
    private val cameraFlow = MutableStateFlow<MapCamera?>(null)
    override val camera: StateFlow<MapCamera?> = cameraFlow
    private val gestures = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val userGestures: Flow<Unit> = gestures
    private val clicks = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val pointClicks: Flow<String> = clicks
    private val lineTaps = MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val lineClicks: Flow<String> = lineTaps
    private val taps = MutableSharedFlow<MapTap>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val mapTaps: Flow<MapTap> = taps
    private val presses = MutableSharedFlow<MapPress>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val longPresses: Flow<MapPress> = presses
    private var pressPosition: GeoPoint? = null

    init {
        map.uiSettings.isRotateGesturesEnabled = MapOptions.ROTATE_GESTURES // S009/RF-11
        map.uiSettings.isTiltGesturesEnabled = MapOptions.TILT_GESTURES
        map.uiSettings.isLogoEnabled = MapOptions.MAPLIBRE_ATTRIBUTION // S009/RF-9
        map.uiSettings.isAttributionEnabled = MapOptions.MAPLIBRE_ATTRIBUTION
        map.setMaxZoomPreference(MapOptions.MAX_ZOOM) // S009/RF-12
        map.addOnCameraMoveListener { publishCamera() }
        map.addOnMoveListener(
            object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: MoveGestureDetector) {
                    gestures.tryEmit(Unit) // S009/RF-20
                }

                override fun onMove(detector: MoveGestureDetector) = Unit

                override fun onMoveEnd(detector: MoveGestureDetector) = Unit
            },
        )
        // S060/RF-26: acercar o alejar con los dedos también suelta el seguimiento del replanteo.
        map.addOnScaleListener(
            object : MapLibreMap.OnScaleListener {
                override fun onScaleBegin(detector: StandardScaleGestureDetector) {
                    gestures.tryEmit(Unit)
                }

                override fun onScale(detector: StandardScaleGestureDetector) = Unit

                override fun onScaleEnd(detector: StandardScaleGestureDetector) = Unit
            },
        )
        map.addOnMapClickListener { latLng ->
            val point = pointAt(map.projection.toScreenLocation(latLng))
            val line = if (point == null) lineAt(map.projection.toScreenLocation(latLng)) else null
            if (point != null) {
                clicks.tryEmit(point)
            } else if (line != null) {
                lineTaps.tryEmit(line) // S025/RF-22: tocar una linea abre su ficha
            } else {
                // S040/RF-29: sin punto del proyecto bajo el dedo, el toque puede ser sobre un elemento del dibujo.
                val metres = WebMercator.metresPerPixel(latLng.latitude, map.cameraPosition.zoom)
                taps.tryEmit(MapTap(latLng.latitude, latLng.longitude, metres * DESIGN_TOUCH_DP * mapView.resources.displayMetrics.density))
            }
            point != null || line != null
        }
        map.addOnMapLongClickListener { latLng ->
            // S036/RF-34: el punto, el píxel y la zona visible, que es lo que piden los servicios al consultar las capas.
            val bounds = map.projection.visibleRegion.latLngBounds
            val area = VisibleArea(
                bounds.latitudeSouth,
                bounds.longitudeWest,
                bounds.latitudeNorth,
                bounds.longitudeEast,
                mapView.width,
                mapView.height,
            )
            val screen = map.projection.toScreenLocation(latLng)
            presses.tryEmit(MapPress.of(latLng.latitude, latLng.longitude, screen.x, screen.y, area))
            true
        }
        map.addOnCameraIdleListener {
            publishCamera()
            updateGrid()
            scheduleCadastre(CADASTRE_DELAY_MS)
            requestWmsImages(WmsOneImageLoader.IDLE_DELAY_MS)
        }
        publishCamera()
    }

    private fun publishCamera() {
        val position = map.cameraPosition
        val target = position.target ?: return
        cameraFlow.value = MapCamera(target.latitude, target.longitude, position.zoom, visibleBounds())
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
                addWmsAnchor(style)
                addSurfaceLayers(style)
                addPointLayers(style)
                addLineLayers(style)
                addDesignLayers(style)
                addAlignmentLayers(style)
                addStakeoutLayers(style)
                addBaseLayer(style)
                addCentralLayer(style)
                addPressLayer(style)
                addRoverLayers(style)
                drawWms()
                drawSurfaces()
                drawDesign()
                drawAlignments()
                drawPoints()
                drawLines()
                drawStakeout()
                drawBase()
                drawCentral()
                drawPressMarker()
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

    override fun moveTo(viewport: MapViewport) {
        // El hueco de los paneles va en el mismo movimiento: cambiarlo aparte cortaría la animación a medio camino.
        appliedInsetPx = bottomInsetPx
        val position = CameraPosition.Builder()
            .target(LatLng(viewport.latitudeDeg, viewport.longitudeDeg))
            .zoom(viewport.zoom)
            .padding(0.0, 0.0, 0.0, bottomInsetPx.toDouble())
            .build()
        easing = true
        map.easeCamera(CameraUpdateFactory.newCameraPosition(position), MOVE_MS, easeDone)
    }

    override fun follow(camera: FollowCamera) {
        appliedInsetPx = bottomInsetPx
        val position = CameraPosition.Builder()
            .target(LatLng(camera.latitudeDeg, camera.longitudeDeg))
            .zoom(camera.zoom)
            .bearing(camera.bearingDeg)
            .padding(0.0, 0.0, 0.0, bottomInsetPx.toDouble())
            .build()
        easing = true
        map.easeCamera(CameraUpdateFactory.newCameraPosition(position), FOLLOW_MS, easeDone)
    }

    override fun resetBearing() {
        val current = map.cameraPosition
        if (current.bearing == 0.0) return
        val position = CameraPosition.Builder(current).bearing(0.0).build()
        easing = true
        map.easeCamera(CameraUpdateFactory.newCameraPosition(position), MOVE_MS, easeDone)
    }

    override val visibleShortSideDp: Double
        get() {
            val density = mapView.resources.displayMetrics.density
            val width = mapView.width / density
            val height = (mapView.height - bottomInsetPx) / density
            return minOf(width, height).toDouble().coerceAtLeast(1.0)
        }

    private val easeDone = object : MapLibreMap.CancelableCallback {
        override fun onFinish() = endEase()

        override fun onCancel() = endEase()
    }

    private fun endEase() {
        easing = false
        applyInset()
    }

    override fun visibleBounds(): GeoBox? = runCatching {
        val bounds = map.projection.visibleRegion.latLngBounds
        GeoBox(bounds.latitudeSouth, bounds.longitudeWest, bounds.latitudeNorth, bounds.longitudeEast)
    }.getOrNull()

    override fun fitBounds(box: GeoBox) {
        gestures.tryEmit(Unit) // S061/RF-15: como mover el mapa a mano
        val bounds = LatLngBounds.from(box.north, box.east, box.south, box.west)
        appliedInsetPx = bottomInsetPx
        easing = true
        map.easeCamera(
            CameraUpdateFactory.newLatLngBounds(bounds, FIT_PADDING_PX, FIT_PADDING_PX, FIT_PADDING_PX, FIT_PADDING_PX + bottomInsetPx),
            MOVE_MS,
            easeDone,
        )
    }

    override fun showSpain() {
        val spain = LatLngBounds.from(CameraPolicy.SPAIN_NORTH, CameraPolicy.SPAIN_EAST, CameraPolicy.SPAIN_SOUTH, CameraPolicy.SPAIN_WEST)
        val p = SPAIN_PADDING_PX
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(spain, p, p, p, p + bottomInsetPx))
        // Mismo centro en pantalla, pero solo con el hueco de los paneles para lo que venga después.
        appliedInsetPx = bottomInsetPx
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, bottomInsetPx.toDouble()))
    }

    override fun setBottomInset(px: Int) {
        bottomInsetPx = px
        if (!easing) applyInset() // S010/RF-1
    }

    private fun applyInset() {
        if (appliedInsetPx == bottomInsetPx) return
        appliedInsetPx = bottomInsetPx
        map.moveCamera(CameraUpdateFactory.paddingTo(0.0, 0.0, 0.0, bottomInsetPx.toDouble()))
    }

    /** El punto bajo un toque, en un cuadro de 48 dp para que se acierte con el dedo (S011/RF-5). */
    private fun pointAt(screen: PointF): String? {
        val half = POINT_TOUCH_DP * mapView.resources.displayMetrics.density / 2
        val box = RectF(screen.x - half, screen.y - half, screen.x + half, screen.y + half)
        val layers = arrayOf(POINTS_LAYER, CODE_LAYER, STAKED_LAYER, SLOPE_LAYER, POINT_LABELS_LAYER)
        return map.queryRenderedFeatures(box, *layers).firstNotNullOfOrNull { it.getStringProperty(POINT_ID) }
    }

    /** La linea del proyecto bajo un toque, en un cuadro de 48 dp (S025/RF-22). */
    private fun lineAt(screen: PointF): String? {
        val half = POINT_TOUCH_DP * mapView.resources.displayMetrics.density / 2
        val box = RectF(screen.x - half, screen.y - half, screen.x + half, screen.y + half)
        return map.queryRenderedFeatures(box, LINES_LAYER, LINES_FILL_LAYER).firstNotNullOfOrNull { it.getStringProperty(LINE_ID) }
    }

    override fun showLines(scene: LineScene?) {
        lineScene = scene
        drawLines()
    }

    /**
     * Las lineas y los poligonos del proyecto: un relleno translucido para los poligonos, el trazo con el color de su codigo (mas grueso
     * en la linea activa) y el nombre a lo largo, todo debajo de los puntos (S025/RF-22).
     */
    private fun addLineLayers(style: Style) {
        style.addSource(GeoJsonSource(LINES_SOURCE))
        style.addLayerBelow(
            FillLayer(LINES_FILL_LAYER, LINES_SOURCE).withFilter(Expression.get(LINE_POLYGON)).withProperties(
                PropertyFactory.fillColor(Expression.toColor(Expression.get(LINE_COLOR))),
                PropertyFactory.fillOpacity(LINE_FILL_OPACITY),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            LineLayer(LINES_LAYER, LINES_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get(LINE_COLOR))),
                PropertyFactory.lineWidth(
                    Expression.switchCase(Expression.get(LINE_ACTIVE), Expression.literal(LINE_ACTIVE_DP), Expression.literal(LINE_DP)),
                ),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            SymbolLayer(LINE_LABELS_LAYER, LINES_SOURCE).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.textField(Expression.get(NAME)),
                PropertyFactory.textFont(arrayOf(MapStyles.LABEL_FONT)),
                PropertyFactory.textSize(LABEL_SIZE_SP),
                PropertyFactory.textColor(Expression.toColor(Expression.get(LINE_COLOR))),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(1.5f),
            ),
            POINTS_LAYER,
        )
    }

    private fun drawLines() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<GeoJsonSource>(LINES_SOURCE) ?: return
        // El GeoJSON ya viene escrito: MapLibre lo lee en su propio hilo y aqui no se crea ni un vertice (RNF-T13).
        source.setGeoJson(lineScene?.geoJson ?: EMPTY_GEOJSON)
    }

    override fun showWmsLayers(plan: WmsLayerPlan) {
        wmsPlan = plan
        drawWms()
    }

    /**
     * Una capa de fondo invisible, encima del mapa base y debajo de todo lo demás que se añade después (líneas, dibujos, ejes, puntos, el
     * Catastro, la base y el rover): las capas WMS se ponen justo debajo de ella, así que quedan sobre el mapa base y bajo todo eso, y
     * cambiarlas nunca reordena el resto (S036/RF-22, RF-33, A5 del plan). Un estilo nuevo no trae las capas WMS: se vuelven a poner.
     */
    private fun addWmsAnchor(style: Style) {
        style.addLayer(BackgroundLayer(WMS_ANCHOR_LAYER).withProperties(PropertyFactory.visibility(Property.NONE)))
        appliedWms = WmsLayerPlan.EMPTY
    }

    /** Lleva el estilo al plan de las capas WMS rehaciendo solo lo que cambia (S036/RF-21, RF-22, RF-29, RF-33). */
    private fun drawWms() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val change = WmsLayerChange.between(appliedWms, wmsPlan)
        when (change) {
            WmsLayerChange.None -> Unit
            is WmsLayerChange.Opacity -> change.layers.forEach { layer ->
                style.getLayer(wmsLayerId(layer.id))?.setProperties(PropertyFactory.rasterOpacity(layer.opacity))
            }
            WmsLayerChange.Rebuild -> {
                appliedWms.layers.forEach { layer ->
                    style.removeLayer(wmsLayerId(layer.id))
                    style.removeSource(wmsSourceId(layer.id))
                }
                // De la de abajo a la de arriba, cada una justo debajo de la ancla: la primera de la lista acaba encima.
                wmsPlan.layers.forEach { addWmsLayer(style, it) }
            }
        }
        appliedWms = wmsPlan
        // Las capas en «una imagen» se piden de nuevo (o se anula lo que había si ya no queda ninguna) (S036/RF-17).
        if (change == WmsLayerChange.Rebuild) requestWmsImages(0)
    }

    private fun addWmsLayer(style: Style, layer: WmsPlanLayer) {
        val sourceId = wmsSourceId(layer.id)
        when (val source = layer.source) {
            is WmsLayerSource.Tiles -> style.addSource(
                RasterSource(sourceId, TileSet(TILEJSON_VERSION, source.template), WmsRequests.TILE_PX),
            )
            // Una imagen vacía hasta que llegue la de la zona visible: así la capa ya está en su sitio del orden (S036/RF-17, RF-22).
            is WmsLayerSource.Image -> {
                val area = map.projection.visibleRegion.latLngBounds
                val quad = wmsQuad(area.latitudeSouth, area.longitudeWest, area.latitudeNorth, area.longitudeEast)
                style.addSource(ImageSource(sourceId, quad, Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
            }
        }
        val raster = RasterLayer(wmsLayerId(layer.id), sourceId).withProperties(PropertyFactory.rasterOpacity(layer.opacity))
        style.addLayerBelow(raster, WMS_ANCHOR_LAYER)
    }

    /** Pide las imágenes de las capas WMS en «una imagen» para la zona visible, con la espera de [delayMs] (S036/RF-17, A4 del plan). */
    private fun requestWmsImages(delayMs: Long) {
        if (map.style?.isFullyLoaded != true || mapView.width <= 0 || mapView.height <= 0) return
        val bounds = map.projection.visibleRegion.latLngBounds
        val area = VisibleArea(
            bounds.latitudeSouth,
            bounds.longitudeWest,
            bounds.latitudeNorth,
            bounds.longitudeEast,
            mapView.width,
            mapView.height,
        )
        wmsImages.request(area, appliedWms.layers, delayMs)
    }

    /** Pone la imagen de una capa en la zona con que se pidió, que es la que cubren sus cuatro esquinas. */
    private fun showWmsImage(loaded: WmsLoadedImage<Bitmap>) {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<ImageSource>(wmsSourceId(loaded.layerId)) ?: return
        val area = loaded.area
        source.setCoordinates(wmsQuad(area.south, area.west, area.north, area.east))
        source.setImage(loaded.image)
    }

    /** El mapa no gira ni se inclina: la zona visible es un rectángulo norte-sur. */
    private fun wmsQuad(south: Double, west: Double, north: Double, east: Double) =
        LatLngQuad(LatLng(north, west), LatLng(north, east), LatLng(south, east), LatLng(south, west))

    private fun wmsSourceId(id: String) = "$WMS_SOURCE_PREFIX$id"

    private fun wmsLayerId(id: String) = "$WMS_LAYER_PREFIX$id"

    override fun showDesign(scene: DesignScene?) {
        design = scene
        drawDesign()
    }

    override fun showSurfaces(scene: SurfaceScene?) {
        surfaceScene = scene
        drawSurfaces()
    }

    /**
     * Las capas de las superficies, todas justo encima del ancla de las WMS y por tanto debajo de líneas, dibujos y puntos, que se
     * añaden después (S061/RF-12). Cada una se pone justo encima del ancla, así que van de la de arriba a la de abajo: el contorno,
     * las aristas y el relleno; las imágenes de los rásteres, que llegan después, quedan debajo del relleno.
     */
    private fun addSurfaceLayers(style: Style) {
        surfaceImageIds.clear()
        style.addSource(GeoJsonSource(SURFACE_MESH_SOURCE))
        style.addSource(GeoJsonSource(SURFACE_OUTLINE_SOURCE))
        // S061/RF-12: cada capa se pone justo encima del ancla, así que se añaden de la de arriba a la de abajo.
        SurfaceGeoJson.LAYERS_BOTTOM_UP.asReversed().forEach { kind -> style.addLayerAbove(surfaceLayer(kind), WMS_ANCHOR_LAYER) }
    }

    private fun surfaceLayer(kind: SurfaceLayerKind) = when (kind) {
        // RF-10: semitransparente para ver el mapa base
        SurfaceLayerKind.FILL -> FillLayer(SURFACE_FILL_LAYER, SURFACE_MESH_SOURCE).withProperties(
            PropertyFactory.fillColor(Expression.toColor(Expression.get(SurfaceGeoJson.COLOR))),
            PropertyFactory.fillOpacity(SURFACE_FILL_OPACITY),
        )
        SurfaceLayerKind.EDGES -> LineLayer(SURFACE_EDGES_LAYER, SURFACE_MESH_SOURCE).withProperties(
            PropertyFactory.lineColor(SURFACE_EDGE_COLOR),
            PropertyFactory.lineWidth(SURFACE_EDGE_DP),
            PropertyFactory.lineOpacity(SURFACE_EDGE_OPACITY),
        )
        // RF-13: la activa, más gruesa
        SurfaceLayerKind.OUTLINE -> LineLayer(SURFACE_OUTLINE_LAYER, SURFACE_OUTLINE_SOURCE).withProperties(
            PropertyFactory.lineColor(SURFACE_OUTLINE_COLOR),
            PropertyFactory.lineWidth(
                Expression.switchCase(
                    Expression.get(SurfaceGeoJson.ACTIVE),
                    Expression.literal(SURFACE_ACTIVE_OUTLINE_DP),
                    Expression.literal(SURFACE_OUTLINE_DP),
                ),
            ),
        )
    }

    /** La escena de las superficies en las fuentes del estilo; las imágenes de rásteres se añaden, se cambian o se quitan (S061, A5). */
    private fun drawSurfaces() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val scene = surfaceScene
        style.getSourceAs<GeoJsonSource>(SURFACE_MESH_SOURCE)?.setGeoJson(SurfaceGeoJson.mesh(scene?.meshes.orEmpty()))
        style.getSourceAs<GeoJsonSource>(SURFACE_OUTLINE_SOURCE)?.setGeoJson(SurfaceGeoJson.outlines(scene?.outlines.orEmpty()))
        val wanted = scene?.images.orEmpty().associateBy { it.surfaceId }
        (surfaceImageIds - wanted.keys).forEach { id ->
            style.removeLayer(SURFACE_IMAGE_LAYER_PREFIX + id)
            style.removeSource(SURFACE_IMAGE_SOURCE_PREFIX + id)
            surfaceImageIds -= id
        }
        for ((id, image) in wanted) {
            val c = image.cornersLonLat
            val quad = LatLngQuad(LatLng(c[1], c[0]), LatLng(c[3], c[2]), LatLng(c[5], c[4]), LatLng(c[7], c[6]))
            val bitmap = Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            val source = style.getSourceAs<ImageSource>(SURFACE_IMAGE_SOURCE_PREFIX + id)
            if (source == null) {
                style.addSource(ImageSource(SURFACE_IMAGE_SOURCE_PREFIX + id, quad, bitmap))
                style.addLayerAbove(
                    RasterLayer(SURFACE_IMAGE_LAYER_PREFIX + id, SURFACE_IMAGE_SOURCE_PREFIX + id).withProperties(
                        PropertyFactory.rasterOpacity(SURFACE_FILL_OPACITY),
                        PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_NEAREST),
                    ),
                    WMS_ANCHOR_LAYER,
                ) // RF-9: una celda por píxel, sin suavizar
                surfaceImageIds += id
            } else {
                source.setCoordinates(quad)
                source.setImage(bitmap)
            }
        }
    }

    /**
     * El dibujo de un DXF: líneas de su color y puntos como anillos huecos, para distinguirlos de los puntos del proyecto (círculos
     * rellenos con nombre), debajo de ellos (S040/RF-24).
     */
    private fun addDesignLayers(style: Style) {
        style.addSource(GeoJsonSource(DESIGN_LINES_SOURCE))
        style.addSource(GeoJsonSource(DESIGN_POINTS_SOURCE))
        // Las líneas de siempre, de su color, y los contornos de polígono de un GeoPackage a trazos (S048/RF-17, RF-23).
        style.addLayerBelow(
            LineLayer(DESIGN_LINES_LAYER, DESIGN_LINES_SOURCE).withFilter(Expression.not(Expression.get(DESIGN_RING))).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get(DESIGN_COLOR))),
                PropertyFactory.lineWidth(DESIGN_LINE_DP),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            LineLayer(DESIGN_RINGS_LAYER, DESIGN_LINES_SOURCE).withFilter(Expression.get(DESIGN_RING)).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get(DESIGN_COLOR))),
                PropertyFactory.lineWidth(DESIGN_LINE_DP),
                PropertyFactory.lineDasharray(arrayOf(DESIGN_DASH_ON, DESIGN_DASH_OFF)),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            CircleLayer(DESIGN_POINTS_LAYER, DESIGN_POINTS_SOURCE).withProperties(
                PropertyFactory.circleRadius(DESIGN_POINT_RADIUS_DP),
                PropertyFactory.circleColor(Color.TRANSPARENT),
                PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get(DESIGN_COLOR))),
                PropertyFactory.circleStrokeWidth(DESIGN_POINT_STROKE_DP),
            ),
            POINTS_LAYER,
        )
        // El nombre de los objetos de un GeoPackage (S048/RF-22): junto al punto y a lo largo de la línea.
        style.addLayerBelow(
            SymbolLayer(DESIGN_POINT_LABELS_LAYER, DESIGN_POINTS_SOURCE).withFilter(Expression.has(DESIGN_NAME)).withProperties(
                PropertyFactory.textField(Expression.get(DESIGN_NAME)),
                PropertyFactory.textFont(arrayOf(MapStyles.LABEL_FONT)),
                PropertyFactory.textSize(LABEL_SIZE_SP),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, DESIGN_LABEL_OFFSET_EM)),
                PropertyFactory.textColor(Expression.toColor(Expression.get(DESIGN_COLOR))),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(DESIGN_LABEL_HALO_DP),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            SymbolLayer(DESIGN_LINE_LABELS_LAYER, DESIGN_LINES_SOURCE).withFilter(Expression.has(DESIGN_NAME)).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.textField(Expression.get(DESIGN_NAME)),
                PropertyFactory.textFont(arrayOf(MapStyles.LABEL_FONT)),
                PropertyFactory.textSize(LABEL_SIZE_SP),
                PropertyFactory.textColor(Expression.toColor(Expression.get(DESIGN_COLOR))),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(DESIGN_LABEL_HALO_DP),
            ),
            POINTS_LAYER,
        )
    }

    override fun showAlignments(scene: AlignmentScene?) {
        alignmentScene = scene
        drawAlignments()
    }

    /**
     * Los ejes: líneas más gruesas y de otro color que las del dibujo, el activo aún más grueso y de color vivo (el estado también va en
     * texto en el panel, RNF-T8), con una marca y el rótulo del PK en cada marca (S049/RF-17). Debajo de los puntos del proyecto.
     */
    private fun addAlignmentLayers(style: Style) {
        style.addSource(GeoJsonSource(ALIGNMENT_LINES_SOURCE))
        style.addSource(GeoJsonSource(ALIGNMENT_MARKS_SOURCE))
        style.addSource(GeoJsonSource(SECTION_LINES_SOURCE))
        style.addLayerBelow(
            LineLayer(SECTION_LINES_LAYER, SECTION_LINES_SOURCE).withProperties(
                PropertyFactory.lineColor(Color.parseColor(SECTION_LINE_COLOR)),
                PropertyFactory.lineWidth(SECTION_LINE_DP),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            SymbolLayer(SECTION_LINE_LABELS_LAYER, SECTION_LINES_SOURCE).withProperties(
                PropertyFactory.symbolPlacement(Property.SYMBOL_PLACEMENT_LINE),
                PropertyFactory.textField(Expression.get(SECTION_CODE)),
                PropertyFactory.textFont(arrayOf(MapStyles.LABEL_FONT)),
                PropertyFactory.textSize(LABEL_SIZE_SP),
                PropertyFactory.textColor(Color.parseColor(SECTION_LINE_COLOR)),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(DESIGN_LABEL_HALO_DP),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            LineLayer(ALIGNMENT_LINES_LAYER, ALIGNMENT_LINES_SOURCE).withProperties(
                PropertyFactory.lineColor(
                    Expression.switchCase(
                        Expression.get(ALIGNMENT_ACTIVE),
                        Expression.color(Color.parseColor(ALIGNMENT_ACTIVE_COLOR)),
                        Expression.color(Color.parseColor(ALIGNMENT_COLOR)),
                    ),
                ),
                PropertyFactory.lineWidth(
                    Expression.switchCase(
                        Expression.get(ALIGNMENT_ACTIVE),
                        Expression.literal(ALIGNMENT_ACTIVE_DP),
                        Expression.literal(ALIGNMENT_DP),
                    ),
                ),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            CircleLayer(ALIGNMENT_MARKS_LAYER, ALIGNMENT_MARKS_SOURCE).withProperties(
                PropertyFactory.circleRadius(ALIGNMENT_MARK_RADIUS_DP),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeColor(
                    Expression.switchCase(
                        Expression.get(ALIGNMENT_ACTIVE),
                        Expression.color(Color.parseColor(ALIGNMENT_ACTIVE_COLOR)),
                        Expression.color(Color.parseColor(ALIGNMENT_COLOR)),
                    ),
                ),
                PropertyFactory.circleStrokeWidth(ALIGNMENT_MARK_STROKE_DP),
            ),
            POINTS_LAYER,
        )
        style.addLayerBelow(
            SymbolLayer(ALIGNMENT_LABELS_LAYER, ALIGNMENT_MARKS_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get(ALIGNMENT_LABEL)),
                PropertyFactory.textFont(arrayOf(MapStyles.LABEL_FONT)),
                PropertyFactory.textSize(LABEL_SIZE_SP),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, DESIGN_LABEL_OFFSET_EM)),
                PropertyFactory.textColor(
                    Expression.switchCase(
                        Expression.get(ALIGNMENT_ACTIVE),
                        Expression.color(Color.parseColor(ALIGNMENT_ACTIVE_COLOR)),
                        Expression.color(Color.parseColor(ALIGNMENT_COLOR)),
                    ),
                ),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(DESIGN_LABEL_HALO_DP),
            ),
            POINTS_LAYER,
        )
    }

    private fun drawAlignments() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val lines = style.getSourceAs<GeoJsonSource>(ALIGNMENT_LINES_SOURCE) ?: return
        val marks = style.getSourceAs<GeoJsonSource>(ALIGNMENT_MARKS_SOURCE) ?: return
        val scene = alignmentScene
        val lineFeatures = scene?.lines.orEmpty().map { line ->
            val coordinates = List(line.vertexCount) { Point.fromLngLat(line.lonLat[it * 2], line.lonLat[it * 2 + 1]) }
            Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply { addBooleanProperty(ALIGNMENT_ACTIVE, line.active) }
        }
        val markFeatures = scene?.marks.orEmpty().map { mark ->
            Feature.fromGeometry(Point.fromLngLat(mark.longitudeDeg, mark.latitudeDeg)).apply {
                addStringProperty(ALIGNMENT_LABEL, mark.label)
                addBooleanProperty(ALIGNMENT_ACTIVE, mark.active)
            }
        }
        lines.setGeoJson(FeatureCollection.fromFeatures(lineFeatures))
        marks.setGeoJson(FeatureCollection.fromFeatures(markFeatures))
        // S050/RF-14: una línea por código de sección, con el código como rótulo.
        style.getSourceAs<GeoJsonSource>(SECTION_LINES_SOURCE)?.setGeoJson(
            FeatureCollection.fromFeatures(
                scene?.sectionLines.orEmpty().map { line ->
                    val coordinates = List(line.vertexCount) { Point.fromLngLat(line.lonLat[it * 2], line.lonLat[it * 2 + 1]) }
                    Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply { addStringProperty(SECTION_CODE, line.code) }
                },
            ),
        )
    }

    private fun drawDesign() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val lines = style.getSourceAs<GeoJsonSource>(DESIGN_LINES_SOURCE) ?: return
        val points = style.getSourceAs<GeoJsonSource>(DESIGN_POINTS_SOURCE) ?: return
        val scene = design
        val lineFeatures = scene?.lines.orEmpty().map { line ->
            val coordinates = List(line.vertexCount) { Point.fromLngLat(line.lonLat[it * 2], line.lonLat[it * 2 + 1]) }
            Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply {
                addStringProperty(DESIGN_COLOR, hex(line.argb))
                addBooleanProperty(DESIGN_RING, line.ring)
                line.name?.let { addStringProperty(DESIGN_NAME, it) }
            }
        }
        val pointFeatures = scene?.points.orEmpty().map { point ->
            Feature.fromGeometry(Point.fromLngLat(point.longitudeDeg, point.latitudeDeg)).apply {
                addStringProperty(DESIGN_COLOR, hex(point.argb))
                point.name?.let { addStringProperty(DESIGN_NAME, it) }
            }
        }
        lines.setGeoJson(FeatureCollection.fromFeatures(lineFeatures))
        points.setGeoJson(FeatureCollection.fromFeatures(pointFeatures))
    }

    private fun hex(argb: Int) = String.format(java.util.Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    override fun showPoints(points: List<MapPoint>) {
        this.points = points
        drawPoints()
    }

    /** Puntos y sus nombres, encima del Catastro y debajo del rover (S010/RF-20). */
    private fun addPointLayers(style: Style) {
        style.addSource(GeoJsonSource(POINTS_SOURCE))
        codeIcons.clear() // un estilo nuevo no trae los iconos de los codigos
        // S024/RF-17: los puntos sin codigo y los de simbolo circulo, con el color de su codigo; fuera de tolerancia conservan el suyo.
        style.addLayer(
            CircleLayer(POINTS_LAYER, POINTS_SOURCE)
                .withFilter(
                    Expression.all(
                        Expression.not(Expression.get(STAKED)),
                        Expression.eq(Expression.get(SYMBOL), Expression.literal(CIRCLE)),
                    ),
                )
                .withProperties(
                    PropertyFactory.circleRadius(POINT_RADIUS_DP),
                    PropertyFactory.circleColor(
                        Expression.switchCase(
                            Expression.get(OUT_OF_TOLERANCE),
                            Expression.color(Color.parseColor(OUT_OF_TOLERANCE_COLOR)),
                            Expression.neq(Expression.get(COLOR), Expression.literal("")),
                            Expression.toColor(Expression.get(COLOR)),
                            Expression.color(Color.parseColor(POINT_COLOR)),
                        ),
                    ),
                    PropertyFactory.circleStrokeColor(Color.WHITE),
                    PropertyFactory.circleStrokeWidth(1.5f),
                ),
        )
        // S024/RF-17: el resto de simbolos (cuadrado, triangulo, rombo, cruz, estrella) con un icono por simbolo y color.
        style.addLayer(
            SymbolLayer(CODE_LAYER, POINTS_SOURCE)
                .withFilter(
                    Expression.all(
                        Expression.not(Expression.get(STAKED)),
                        Expression.neq(Expression.get(SYMBOL), Expression.literal(CIRCLE)),
                    ),
                )
                .withProperties(
                    PropertyFactory.iconImage(Expression.get(ICON)),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
        )
        // S018/RF-34: un punto replanteado es un rombo, distinto del círculo de los medidos.
        style.addImage(STAKED_ICON, stakedIcon())
        style.addLayer(
            SymbolLayer(STAKED_LAYER, POINTS_SOURCE)
                .withFilter(Expression.all(Expression.get(STAKED), Expression.eq(Expression.get(SLOPE_KIND), Expression.literal(""))))
                .withProperties(
                    PropertyFactory.iconImage(STAKED_ICON),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
        )
        // S051/RF-16: un punto de talud es un triángulo: hacia arriba la cabeza de desmonte, hacia abajo el pie de terraplén.
        style.addImage(SLOPE_CUT_ICON, slopeIcon(up = true))
        style.addImage(SLOPE_FILL_ICON, slopeIcon(up = false))
        style.addLayer(
            SymbolLayer(SLOPE_LAYER, POINTS_SOURCE)
                .withFilter(Expression.neq(Expression.get(SLOPE_KIND), Expression.literal("")))
                .withProperties(
                    PropertyFactory.iconImage(
                        Expression.switchCase(
                            Expression.eq(Expression.get(SLOPE_KIND), Expression.literal(SlopeKind.CUT.name)),
                            Expression.literal(SLOPE_CUT_ICON),
                            Expression.literal(SLOPE_FILL_ICON),
                        ),
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                ),
        )
        style.addLayer(
            SymbolLayer(POINT_LABELS_LAYER, POINTS_SOURCE).withProperties(
                PropertyFactory.textField(Expression.get(NAME)),
                PropertyFactory.textFont(arrayOf(MapStyles.LABEL_FONT)),
                PropertyFactory.textSize(LABEL_SIZE_SP),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 0.8f)),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(1.5f),
            ),
        )
    }

    private fun drawPoints() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<GeoJsonSource>(POINTS_SOURCE) ?: return
        val features = points.map { point ->
            Feature.fromGeometry(Point.fromLngLat(point.position.longitudeDeg, point.position.latitudeDeg)).apply {
                addStringProperty(POINT_ID, point.id)
                addStringProperty(NAME, point.name)
                addBooleanProperty(OUT_OF_TOLERANCE, point.outOfTolerance)
                addBooleanProperty(STAKED, point.staked)
                addStringProperty(SLOPE_KIND, point.slope?.name.orEmpty())
                addStringProperty(SYMBOL, point.symbol.name)
                addStringProperty(COLOR, point.color?.let { "#%06X".format(java.util.Locale.ROOT, it and 0xFFFFFF) }.orEmpty())
                if (point.symbol != CodeSymbol.CIRCLE) {
                    // El icono lleva el color de su codigo, o el del aviso si el punto esta fuera de tolerancia (S024/RF-17).
                    val rgb = SymbolShapes.effectiveColor(point.color, point.outOfTolerance)
                    val id = SymbolShapes.iconId(point.symbol, rgb)
                    if (codeIcons.add(id)) style.addImage(id, codeIcon(point.symbol, rgb))
                    addStringProperty(ICON, id)
                }
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
        MapPoints.labelMinZoom(points)?.let { style.getLayer(POINT_LABELS_LAYER)?.minZoom = it.toFloat() }
    }

    override fun showStakeout(marker: StakeoutMarker?) {
        stakeout = marker
        drawStakeout()
    }

    /** La línea punta-diseño y los dos puntos, debajo del rover y encima de los puntos (S018/RF-14). */
    private fun addStakeoutLayers(style: Style) {
        style.addSource(GeoJsonSource(STAKEOUT_SOURCE))
        val lines = Expression.all(
            Expression.eq(Expression.geometryType(), "LineString"),
            Expression.eq(Expression.get(STAKEOUT_ROLE), STAKEOUT_GUIDE),
        )
        val chosen = Expression.eq(Expression.get(STAKEOUT_ROLE), STAKEOUT_CHOSEN)
        val designs = Expression.eq(Expression.get(STAKEOUT_ROLE), STAKEOUT_DESIGN)
        // S041/RF-5: la línea elegida, más gruesa que las del dibujo, debajo de la guía y de los puntos
        style.addLayer(
            LineLayer(STAKEOUT_CHOSEN_LAYER, STAKEOUT_SOURCE).withFilter(chosen).withProperties(
                PropertyFactory.lineColor(Color.parseColor(STAKEOUT_COLOR)),
                PropertyFactory.lineWidth(STAKEOUT_CHOSEN_DP),
                PropertyFactory.lineOpacity(STAKEOUT_CHOSEN_OPACITY),
            ),
        )
        val tips = Expression.eq(Expression.get(STAKEOUT_ROLE), STAKEOUT_TIP)
        style.addLayer(
            LineLayer(STAKEOUT_LINE_LAYER, STAKEOUT_SOURCE).withFilter(lines).withProperties(
                PropertyFactory.lineColor(Color.parseColor(STAKEOUT_COLOR)),
                PropertyFactory.lineWidth(STAKEOUT_LINE_DP),
                PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            ),
        )
        style.addLayer(
            CircleLayer(STAKEOUT_DESIGN_LAYER, STAKEOUT_SOURCE).withFilter(designs).withProperties(
                PropertyFactory.circleRadius(STAKEOUT_DESIGN_RADIUS_DP),
                PropertyFactory.circleColor(Color.TRANSPARENT),
                PropertyFactory.circleStrokeColor(Color.parseColor(STAKEOUT_COLOR)),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        style.addLayer(
            CircleLayer(STAKEOUT_TIP_LAYER, STAKEOUT_SOURCE).withFilter(tips).withProperties(
                PropertyFactory.circleRadius(POINT_RADIUS_DP),
                PropertyFactory.circleColor(Color.parseColor(STAKEOUT_COLOR)),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
    }

    private fun drawStakeout() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<GeoJsonSource>(STAKEOUT_SOURCE) ?: return
        val marker = stakeout
        if (marker == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            return
        }
        val design = marker.design?.let { Point.fromLngLat(it.longitudeDeg, it.latitudeDeg) }
        val features = mutableListOf<Feature>()
        if (marker.chosen.size >= 2) {
            val chosen = LineString.fromLngLats(marker.chosen.map { Point.fromLngLat(it.longitudeDeg, it.latitudeDeg) })
            features += Feature.fromGeometry(chosen).apply { addStringProperty(STAKEOUT_ROLE, STAKEOUT_CHOSEN) }
        }
        if (design != null) features += Feature.fromGeometry(design).apply { addStringProperty(STAKEOUT_ROLE, STAKEOUT_DESIGN) }
        marker.tip?.let { tip ->
            val tipPoint = Point.fromLngLat(tip.longitudeDeg, tip.latitudeDeg)
            features += Feature.fromGeometry(tipPoint).apply { addStringProperty(STAKEOUT_ROLE, STAKEOUT_TIP) }
            if (marker.line && design != null) {
                features += Feature.fromGeometry(LineString.fromLngLats(listOf(tipPoint, design))).apply {
                    addStringProperty(STAKEOUT_ROLE, STAKEOUT_GUIDE)
                }
            }
        }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    /** Los iconos de simbolo y color que ya estan en el estilo (S024/RF-17). */
    private val codeIcons = HashSet<String>()

    /** Una forma de [SymbolShapes] de color [rgb] con borde blanco, para los puntos con codigo que no son circulos (S024/RF-17). */
    private fun codeIcon(symbol: CodeSymbol, rgb: Int): Bitmap {
        val density = mapView.resources.displayMetrics.density
        val size = (CODE_ICON_DP * density).roundToInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val path = Path()
        SymbolShapes.polygon(symbol).forEachIndexed { i, (x, y) ->
            if (i == 0) path.moveTo(x * size, y * size) else path.lineTo(x * size, y * size)
        }
        path.close()
        val canvas = Canvas(bitmap)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(Color.red(rgb), Color.green(rgb), Color.blue(rgb)) })
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = STAKED_STROKE_DP * density
            },
        )
        return bitmap
    }

    /** Un rombo con borde blanco para los puntos replanteados, distinto del círculo de los medidos y del triángulo de la base. */
    private fun stakedIcon(): Bitmap {
        val density = mapView.resources.displayMetrics.density
        val size = (STAKED_ICON_DP * density).roundToInt()
        val inset = STAKED_STROKE_DP * density
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(size / 2f, inset)
            lineTo(size - inset, size / 2f)
            lineTo(size / 2f, size - inset)
            lineTo(inset, size / 2f)
            close()
        }
        val canvas = Canvas(bitmap)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor(STAKED_COLOR) })
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = inset
            },
        )
        return bitmap
    }

    /** Un triángulo con borde blanco para los puntos de talud: apunta arriba en la cabeza y abajo en el pie (S051/RF-16). */
    private fun slopeIcon(up: Boolean): Bitmap {
        val density = mapView.resources.displayMetrics.density
        val size = (STAKED_ICON_DP * density).roundToInt()
        val inset = STAKED_STROKE_DP * density
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val tip = if (up) inset else size - inset
        val base = if (up) size - inset else inset
        val path = Path().apply {
            moveTo(size / 2f, tip)
            lineTo(size - inset, base)
            lineTo(inset, base)
            close()
        }
        val canvas = Canvas(bitmap)
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor(if (up) SLOPE_CUT_COLOR else SLOPE_FILL_COLOR)
            },
        )
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = inset
            },
        )
        return bitmap
    }

    override fun showRover(marker: RoverMarker?) {
        rover = marker
        drawRover()
    }

    override fun showPressMarker(position: GeoPoint?) {
        pressPosition = position
        drawPressMarker()
    }

    /** La marca de la pulsación larga: un anillo magenta con relleno claro, distinto de los puntos, la base y el rover (S036/RF-34). */
    private fun addPressLayer(style: Style) {
        style.addSource(GeoJsonSource(PRESS_SOURCE))
        style.addLayer(
            CircleLayer(PRESS_LAYER, PRESS_SOURCE).withProperties(
                PropertyFactory.circleRadius(PRESS_RADIUS_DP),
                PropertyFactory.circleColor(Color.parseColor(PRESS_COLOR)),
                PropertyFactory.circleOpacity(PRESS_FILL_OPACITY),
                PropertyFactory.circleStrokeColor(Color.parseColor(PRESS_COLOR)),
                PropertyFactory.circleStrokeWidth(PRESS_STROKE_DP),
            ),
        )
    }

    private fun drawPressMarker() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<GeoJsonSource>(PRESS_SOURCE) ?: return
        val position = pressPosition
        if (position == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.longitudeDeg, position.latitudeDeg)))
        }
    }

    override fun showBase(position: GeoPoint?) {
        base = position
        drawBase()
    }

    override fun showCentralPoint(position: GeoPoint?, label: String) {
        central = position
        centralLabel = label
        drawCentral()
    }

    /** El punto central del proyecto: una diana con su texto, encima de la base y debajo del rover (S006/RF-36). */
    private fun addCentralLayer(style: Style) {
        style.addImage(CENTRAL_ICON, centralIcon())
        style.addSource(GeoJsonSource(CENTRAL_SOURCE))
        style.addLayer(
            SymbolLayer(CENTRAL_LAYER, CENTRAL_SOURCE).withProperties(
                PropertyFactory.iconImage(CENTRAL_ICON),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.textField(Expression.get(NAME)),
                PropertyFactory.textFont(arrayOf(MapStyles.LABEL_FONT)),
                PropertyFactory.textSize(LABEL_SIZE_SP),
                PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                PropertyFactory.textOffset(arrayOf(0f, 1.2f)),
                PropertyFactory.textAllowOverlap(true),
                PropertyFactory.textHaloColor(Color.WHITE),
                PropertyFactory.textHaloWidth(1.5f),
            ),
        )
    }

    private fun drawCentral() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<GeoJsonSource>(CENTRAL_SOURCE) ?: return
        val position = central
        if (position == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(
                Feature.fromGeometry(Point.fromLngLat(position.longitudeDeg, position.latitudeDeg)).apply {
                    addStringProperty(NAME, centralLabel)
                },
            )
        }
    }

    /** Un círculo con una cruz dentro, oscuro con borde blanco: distinto del triángulo de la base, el rombo, el círculo del rover y los puntos. */
    private fun centralIcon(): Bitmap {
        val density = mapView.resources.displayMetrics.density
        val size = (CENTRAL_ICON_DP * density).roundToInt()
        val stroke = CENTRAL_STROKE_DP * density
        val c = size / 2f
        val radius = c - stroke
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        fun paint(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
        }
        // Primero el borde blanco, más ancho, y encima el trazo oscuro: se ve sobre cualquier mapa base.
        for ((color, width) in listOf(Color.WHITE to stroke * 2.5f, CENTRAL_COLOR to stroke)) {
            canvas.drawCircle(c, c, radius, paint(color, width))
            canvas.drawLine(c, stroke, c, size - stroke, paint(color, width))
            canvas.drawLine(stroke, c, size - stroke, c, paint(color, width))
        }
        return bitmap
    }

    /** La base, con un triángulo propio, encima de los puntos y debajo del rover (S015/RF-19). */
    private fun addBaseLayer(style: Style) {
        style.addImage(BASE_ICON, baseIcon())
        style.addSource(GeoJsonSource(BASE_SOURCE))
        style.addLayer(
            SymbolLayer(BASE_LAYER, BASE_SOURCE).withProperties(
                PropertyFactory.iconImage(BASE_ICON),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    private fun drawBase() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val source = style.getSourceAs<GeoJsonSource>(BASE_SOURCE) ?: return
        val position = base
        if (position == null) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
        } else {
            source.setGeoJson(Feature.fromGeometry(Point.fromLngLat(position.longitudeDeg, position.latitudeDeg)))
        }
    }

    /** Triángulo oscuro con borde blanco: el símbolo de una estación, distinto del círculo del rover y de los puntos. */
    private fun baseIcon(): Bitmap {
        val density = mapView.resources.displayMetrics.density
        val size = (BASE_ICON_DP * density).roundToInt()
        val inset = BASE_STROKE_DP * density
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val path = Path().apply {
            moveTo(size / 2f, inset)
            lineTo(size - inset, size - inset)
            lineTo(inset, size - inset)
            close()
        }
        val canvas = Canvas(bitmap)
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BASE_COLOR })
        canvas.drawPath(
            path,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = inset
            },
        )
        return bitmap
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
            // Debajo de los puntos, el rover y su círculo.
            style.addLayerBelow(RasterLayer(CADASTRE_LAYER, CADASTRE_SOURCE), POINTS_LAYER)
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
        const val PRESS_SOURCE = "topophone-press"
        const val PRESS_LAYER = "topophone-press"
        const val PRESS_COLOR = "#D81B60"
        const val PRESS_RADIUS_DP = 11f
        const val PRESS_STROKE_DP = 3f
        const val PRESS_FILL_OPACITY = 0.3f
        const val WMS_ANCHOR_LAYER = "topophone-wms-anchor"
        const val FIT_PADDING_PX = 48
        const val SURFACE_MESH_SOURCE = "topophone-surface-mesh"
        const val SURFACE_OUTLINE_SOURCE = "topophone-surface-outline"
        const val SURFACE_FILL_LAYER = "topophone-surface-fill"
        const val SURFACE_EDGES_LAYER = "topophone-surface-edges"
        const val SURFACE_OUTLINE_LAYER = "topophone-surface-outline"
        const val SURFACE_IMAGE_SOURCE_PREFIX = "topophone-surface-image-source-"
        const val SURFACE_IMAGE_LAYER_PREFIX = "topophone-surface-image-layer-"
        const val SURFACE_OUTLINE_COLOR = "#4A148C"
        const val SURFACE_EDGE_COLOR = "#424242"
        const val SURFACE_OUTLINE_DP = 1.5f
        const val SURFACE_ACTIVE_OUTLINE_DP = 3f
        const val SURFACE_EDGE_DP = 0.5f
        const val SURFACE_EDGE_OPACITY = 0.6f
        const val SURFACE_FILL_OPACITY = 0.55f
        const val WMS_SOURCE_PREFIX = "topophone-wms-source-"
        const val WMS_LAYER_PREFIX = "topophone-wms-layer-"
        const val TILEJSON_VERSION = "2.1.0"
        const val ALIGNMENT_LINES_SOURCE = "topophone-alignment-lines"
        const val ALIGNMENT_LINES_LAYER = "topophone-alignment-lines"
        const val ALIGNMENT_MARKS_SOURCE = "topophone-alignment-marks"
        const val ALIGNMENT_MARKS_LAYER = "topophone-alignment-marks"
        const val ALIGNMENT_LABELS_LAYER = "topophone-alignment-labels"
        const val ALIGNMENT_ACTIVE = "alignment_active"
        const val ALIGNMENT_LABEL = "alignment_label"
        const val SECTION_LINES_SOURCE = "topophone-section-lines"
        const val SECTION_LINES_LAYER = "topophone-section-lines"
        const val SECTION_LINE_LABELS_LAYER = "topophone-section-line-labels"
        const val SECTION_CODE = "section_code"
        const val SECTION_LINE_COLOR = "#00695C"
        const val SECTION_LINE_DP = 1.5f
        const val ALIGNMENT_COLOR = "#6A1B9A"
        const val ALIGNMENT_ACTIVE_COLOR = "#C62828"
        const val ALIGNMENT_DP = 3f
        const val ALIGNMENT_ACTIVE_DP = 5f
        const val ALIGNMENT_MARK_RADIUS_DP = 3.5f
        const val ALIGNMENT_MARK_STROKE_DP = 2f
        const val DESIGN_LINES_SOURCE = "topophone-design-lines"
        const val DESIGN_LINES_LAYER = "topophone-design-lines"
        const val DESIGN_POINTS_SOURCE = "topophone-design-points"
        const val DESIGN_POINTS_LAYER = "topophone-design-points"
        const val DESIGN_RINGS_LAYER = "topophone-design-rings"
        const val DESIGN_POINT_LABELS_LAYER = "topophone-design-point-labels"
        const val DESIGN_LINE_LABELS_LAYER = "topophone-design-line-labels"
        const val DESIGN_NAME = "design_name"
        const val DESIGN_RING = "design_ring"
        const val DESIGN_DASH_ON = 4f
        const val DESIGN_DASH_OFF = 2f
        const val DESIGN_LABEL_OFFSET_EM = 0.6f
        const val DESIGN_LABEL_HALO_DP = 1.5f
        const val DESIGN_COLOR = "color"
        const val DESIGN_TOUCH_DP = 24f
        const val DESIGN_LINE_DP = 1.5f
        const val DESIGN_POINT_RADIUS_DP = 4f
        const val DESIGN_POINT_STROKE_DP = 1.5f
        const val LINES_SOURCE = "topophone-lines"
        const val LINES_LAYER = "topophone-lines"
        const val LINES_FILL_LAYER = "topophone-lines-fill"
        const val LINE_LABELS_LAYER = "topophone-line-labels"
        const val LINE_ID = "id"
        const val LINE_COLOR = "color"
        const val LINE_ACTIVE = "active"
        const val LINE_POLYGON = "polygon"
        const val LINE_DP = 3f
        const val LINE_ACTIVE_DP = 5f
        const val LINE_FILL_OPACITY = 0.25f
        const val EMPTY_GEOJSON = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        const val POINTS_SOURCE = "topophone-points"
        const val POINTS_LAYER = "topophone-points"
        const val POINT_LABELS_LAYER = "topophone-point-labels"
        const val NAME = "name"
        const val POINT_ID = "id"
        const val POINT_TOUCH_DP = 48f
        const val OUT_OF_TOLERANCE = "outOfTolerance"
        const val POINT_COLOR = "#1565C0"
        const val OUT_OF_TOLERANCE_COLOR = "#E65100"
        const val POINT_RADIUS_DP = 5f
        const val STAKED = "staked"
        const val CODE_LAYER = "topophone-code"
        const val SYMBOL = "symbol"
        const val COLOR = "color"
        const val ICON = "icon"
        const val CIRCLE = "CIRCLE"
        const val CODE_ICON_DP = 18f
        const val STAKED_LAYER = "topophone-staked"
        const val STAKED_ICON = "topophone-staked-icon"
        const val STAKED_ICON_DP = 20f
        const val STAKED_STROKE_DP = 1.5f
        const val STAKED_COLOR = "#2E7D32"
        const val SLOPE_KIND = "slopeKind"
        const val SLOPE_LAYER = "topophone-slope"
        const val SLOPE_CUT_ICON = "topophone-slope-cut-icon"
        const val SLOPE_FILL_ICON = "topophone-slope-fill-icon"
        const val SLOPE_CUT_COLOR = "#6A1B9A"
        const val SLOPE_FILL_COLOR = "#EF6C00"
        const val STAKEOUT_SOURCE = "topophone-stakeout"
        const val STAKEOUT_LINE_LAYER = "topophone-stakeout-line"
        const val STAKEOUT_CHOSEN_LAYER = "topophone-stakeout-chosen"
        const val STAKEOUT_DESIGN_LAYER = "topophone-stakeout-design"
        const val STAKEOUT_TIP_LAYER = "topophone-stakeout-tip"
        const val STAKEOUT_ROLE = "role"
        const val STAKEOUT_DESIGN = "design"
        const val STAKEOUT_GUIDE = "guide"
        const val STAKEOUT_CHOSEN = "chosen"
        const val STAKEOUT_CHOSEN_DP = 6f
        const val STAKEOUT_CHOSEN_OPACITY = 0.6f
        const val STAKEOUT_TIP = "tip"
        const val STAKEOUT_COLOR = "#C62828"
        const val STAKEOUT_LINE_DP = 2f
        const val STAKEOUT_DESIGN_RADIUS_DP = 11f
        const val LABEL_SIZE_SP = 12f
        const val ACCURACY_SOURCE = "topophone-accuracy"
        const val ACCURACY_FILL_LAYER = "topophone-accuracy-fill"
        const val ACCURACY_LINE_LAYER = "topophone-accuracy-line"
        const val ROVER_SOURCE = "topophone-rover"
        const val ROVER_LAYER = "topophone-rover"
        const val ROVER_RADIUS_DP = 8f
        const val BASE_SOURCE = "topophone-base"
        const val BASE_LAYER = "topophone-base"
        const val BASE_ICON = "topophone-base-icon"
        const val BASE_ICON_DP = 22f
        const val BASE_STROKE_DP = 2f
        const val BASE_COLOR = 0xFF263238.toInt()
        const val CENTRAL_SOURCE = "topophone-central"
        const val CENTRAL_LAYER = "topophone-central"
        const val CENTRAL_ICON = "topophone-central-icon"
        const val CENTRAL_ICON_DP = 28f
        const val CENTRAL_STROKE_DP = 2f
        const val CENTRAL_COLOR = 0xFF6A1B9A.toInt()
        const val ACCURACY_OPACITY = 0.25f

        /** Desplazamiento suave al seguir al rover, que llega a 5 Hz como mucho (RNF-T13). */
        const val MOVE_MS = 200

        /** La transición de cada orden de seguimiento del replanteo, igual que el intervalo entre órdenes (S060/RF-25, RNF-T13). */
        const val FOLLOW_MS = 200
        const val SPAIN_PADDING_PX = 32

        /** Espera tras detenerse el mapa antes de pedir el Catastro. */
        const val CADASTRE_DELAY_MS = 500L
    }
}

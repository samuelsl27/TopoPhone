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
import es.topophone.app.ui.cad.DesignScene
import es.topophone.core.geodesy.AccuracyCircle
import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.MapGrid
import es.topophone.core.geodesy.WebMercator
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
    private var points: List<MapPoint> = emptyList()
    private var design: DesignScene? = null
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
    private val taps = MutableSharedFlow<MapTap>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val mapTaps: Flow<MapTap> = taps

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
        map.addOnMapClickListener { latLng ->
            val point = pointAt(map.projection.toScreenLocation(latLng))
            if (point != null) {
                clicks.tryEmit(point)
            } else {
                // S040/RF-29: sin punto del proyecto bajo el dedo, el toque puede ser sobre un elemento del dibujo.
                val metres = WebMercator.metresPerPixel(latLng.latitude, map.cameraPosition.zoom)
                taps.tryEmit(MapTap(latLng.latitude, latLng.longitude, metres * DESIGN_TOUCH_DP * mapView.resources.displayMetrics.density))
            }
            point != null
        }
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
                addPointLayers(style)
                addDesignLayers(style)
                addStakeoutLayers(style)
                addBaseLayer(style)
                addRoverLayers(style)
                drawDesign()
                drawPoints()
                drawStakeout()
                drawBase()
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

    private val easeDone = object : MapLibreMap.CancelableCallback {
        override fun onFinish() = endEase()

        override fun onCancel() = endEase()
    }

    private fun endEase() {
        easing = false
        applyInset()
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
        val layers = arrayOf(POINTS_LAYER, STAKED_LAYER, POINT_LABELS_LAYER)
        return map.queryRenderedFeatures(box, *layers).firstNotNullOfOrNull { it.getStringProperty(POINT_ID) }
    }

    override fun showDesign(scene: DesignScene?) {
        design = scene
        drawDesign()
    }

    /**
     * El dibujo de un DXF: líneas de su color y puntos como anillos huecos, para distinguirlos de los puntos del proyecto (círculos
     * rellenos con nombre), debajo de ellos (S040/RF-24).
     */
    private fun addDesignLayers(style: Style) {
        style.addSource(GeoJsonSource(DESIGN_LINES_SOURCE))
        style.addSource(GeoJsonSource(DESIGN_POINTS_SOURCE))
        style.addLayerBelow(
            LineLayer(DESIGN_LINES_LAYER, DESIGN_LINES_SOURCE).withProperties(
                PropertyFactory.lineColor(Expression.toColor(Expression.get(DESIGN_COLOR))),
                PropertyFactory.lineWidth(DESIGN_LINE_DP),
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
    }

    private fun drawDesign() {
        val style = map.style?.takeIf { it.isFullyLoaded } ?: return
        val lines = style.getSourceAs<GeoJsonSource>(DESIGN_LINES_SOURCE) ?: return
        val points = style.getSourceAs<GeoJsonSource>(DESIGN_POINTS_SOURCE) ?: return
        val scene = design
        val lineFeatures = scene?.lines.orEmpty().map { line ->
            val coordinates = List(line.vertexCount) { Point.fromLngLat(line.lonLat[it * 2], line.lonLat[it * 2 + 1]) }
            Feature.fromGeometry(LineString.fromLngLats(coordinates)).apply { addStringProperty(DESIGN_COLOR, hex(line.argb)) }
        }
        val pointFeatures = scene?.points.orEmpty().map { point ->
            Feature.fromGeometry(Point.fromLngLat(point.longitudeDeg, point.latitudeDeg)).apply {
                addStringProperty(DESIGN_COLOR, hex(point.argb))
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
        style.addLayer(
            CircleLayer(POINTS_LAYER, POINTS_SOURCE).withFilter(Expression.not(Expression.get(STAKED))).withProperties(
                PropertyFactory.circleRadius(POINT_RADIUS_DP),
                PropertyFactory.circleColor(
                    Expression.switchCase(
                        Expression.get(OUT_OF_TOLERANCE),
                        Expression.color(Color.parseColor(OUT_OF_TOLERANCE_COLOR)),
                        Expression.color(Color.parseColor(POINT_COLOR)),
                    ),
                ),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(1.5f),
            ),
        )
        // S018/RF-34: un punto replanteado es un rombo, distinto del círculo de los medidos.
        style.addImage(STAKED_ICON, stakedIcon())
        style.addLayer(
            SymbolLayer(STAKED_LAYER, POINTS_SOURCE).withFilter(Expression.get(STAKED)).withProperties(
                PropertyFactory.iconImage(STAKED_ICON),
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

    override fun showRover(marker: RoverMarker?) {
        rover = marker
        drawRover()
    }

    override fun showBase(position: GeoPoint?) {
        base = position
        drawBase()
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
        const val DESIGN_LINES_SOURCE = "topophone-design-lines"
        const val DESIGN_LINES_LAYER = "topophone-design-lines"
        const val DESIGN_POINTS_SOURCE = "topophone-design-points"
        const val DESIGN_POINTS_LAYER = "topophone-design-points"
        const val DESIGN_COLOR = "color"
        const val DESIGN_TOUCH_DP = 24f
        const val DESIGN_LINE_DP = 1.5f
        const val DESIGN_POINT_RADIUS_DP = 4f
        const val DESIGN_POINT_STROKE_DP = 1.5f
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
        const val STAKED_LAYER = "topophone-staked"
        const val STAKED_ICON = "topophone-staked-icon"
        const val STAKED_ICON_DP = 20f
        const val STAKED_STROKE_DP = 1.5f
        const val STAKED_COLOR = "#2E7D32"
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
        const val ACCURACY_OPACITY = 0.25f

        /** Desplazamiento suave al seguir al rover, que llega a 5 Hz como mucho (RNF-T13). */
        const val MOVE_MS = 200
        const val SPAIN_PADDING_PX = 32

        /** Espera tras detenerse el mapa antes de pedir el Catastro. */
        const val CADASTRE_DELAY_MS = 500L
    }
}

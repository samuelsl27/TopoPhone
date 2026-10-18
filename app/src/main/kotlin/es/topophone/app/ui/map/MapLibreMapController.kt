package es.topophone.app.ui.map

import es.topophone.core.geodesy.MapGrid
import es.topophone.core.geodesy.WebMercator
import es.topophone.core.model.map.MapSettings
import es.topophone.core.model.map.ScaleBar
import es.topophone.core.model.project.LinearUnit
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/** [MapController] sobre MapLibre (ADR-9). */
internal class MapLibreMapController(private val map: MapLibreMap) : MapController {
    private var settings: MapSettings? = null
    private var unit = LinearUnit.METRE

    init {
        // S009/RF-11: norte arriba; el mapa no gira ni se inclina.
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
        map.addOnCameraIdleListener { updateGrid() }
    }

    override fun setSettings(settings: MapSettings) {
        if (settings == this.settings) return
        this.settings = settings
        val builder = when (val style = MapStyles.style(settings.basemap)) {
            is BasemapStyle.Remote -> Style.Builder().fromUri(style.url)
            is BasemapStyle.Local -> Style.Builder().fromJson(style.json)
        }
        map.setStyle(builder) { style ->
            addBackground(style)
            updateGrid()
        }
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

    private companion object {
        const val BACKGROUND_LAYER = "topophone-background"
        const val GRID_SOURCE = "topophone-grid"
        const val GRID_LAYER = "topophone-grid"
    }
}

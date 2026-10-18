package es.topophone.app.ui.map

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import es.topophone.app.BuildConfig
import es.topophone.app.R
import es.topophone.core.model.map.MapSettings
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil

/** El cliente HTTP de todo lo que descarga el mapa (S009). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MapHttp

/**
 * Cliente HTTP del mapa, con un User-Agent que identifica la app, como piden las condiciones de
 * OpenFreeMap y del resto de servicios (S009, RNF). S019 lo enlazará a la red con internet (RNF-T4).
 */
object MapHttpClient {
    fun create(versionName: String): OkHttpClient {
        val userAgent = "TopoPhone/$versionName (+${BuildConfig.REPO_URL})"
        return OkHttpClient.Builder()
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
    }
}

/** Puesta en marcha de MapLibre, una vez, al crear el primer mapa: no retrasa el arranque de la app (RNF-T13). */
@Singleton
class MapEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:MapHttp private val client: OkHttpClient,
) {
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(client)
        initialized = true
    }
}

/** Mapa de MapLibre en Compose: crea el `MapView`, le pasa el ciclo de vida y entrega su controlador. */
@Composable
fun MapLibreMap(environment: MapEnvironment, onReady: (MapController) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember {
        environment.ensureInitialized()
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }
    val description = stringResource(R.string.map_description)
    AndroidView(factory = { mapView }, modifier = modifier.semantics { contentDescription = description })
    LaunchedEffect(mapView) { mapView.getMapAsync { map -> onReady(MapLibreMapController(map)) } }
}

/** [MapController] sobre MapLibre. */
internal class MapLibreMapController(private val map: MapLibreMap) : MapController {
    private var settings: MapSettings? = null

    init {
        // S009/RF-11: norte arriba; el mapa no gira ni se inclina.
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isTiltGesturesEnabled = false
    }

    override fun setSettings(settings: MapSettings) {
        if (settings == this.settings) return
        this.settings = settings
        // T6: los demás mapas base y el fondo neutro con cuadrícula.
        map.setStyle(Style.Builder().fromUri(MapStyles.IGN_STYLE_URL))
    }
}

/** Direcciones de los mapas base (plan de S009, "Fuentes de mapas"). */
object MapStyles {
    const val IGN_STYLE_URL = "https://vt-mapabase.idee.es/files/styles/mapaBase_scn_color1_CNIG.json"
}

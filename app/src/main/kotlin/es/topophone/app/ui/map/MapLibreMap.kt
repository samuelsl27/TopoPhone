package es.topophone.app.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import es.topophone.app.BuildConfig
import es.topophone.app.R
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.offline.OfflineManager

/** El cliente HTTP de todo lo que descarga el mapa (S009). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MapHttp

/**
 * Cliente HTTP del mapa, con un User-Agent que identifica la app, como piden las condiciones de
 * OpenFreeMap y del resto de servicios (S009, RNF). S020 lo enlazará a la red con internet (RNF-T4).
 */
object MapHttpClient {
    fun create(versionName: String, monitor: NetworkFailureMonitor? = null): OkHttpClient {
        val userAgent = "TopoPhone/$versionName (+${BuildConfig.REPO_URL})"
        return OkHttpClient.Builder()
            .apply { if (monitor != null) addInterceptor(monitor.interceptor) } // S009/RF-15
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .addInterceptor(IgnStylePatch.interceptor) // S009/RF-6
            .build()
    }
}

/** Puesta en marcha de MapLibre, una vez, al crear el primer mapa: no retrasa el arranque de la app (RNF-T13). */
@Singleton
class MapEnvironment @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:MapHttp val httpClient: OkHttpClient,
    private val monitor: NetworkFailureMonitor,
) {
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        MapLibre.getInstance(context)
        HttpRequestUtil.setOkHttpClient(httpClient)
        watchInternet()
        // S009/RF-13: lo ya visto queda guardado para verlo sin conexión; antes de poner ningún estilo.
        OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
            MapOptions.AMBIENT_CACHE_BYTES,
            object : OfflineManager.FileSourceCallback {
                override fun onSuccess() = Unit

                override fun onError(message: String) = Unit
            },
        )
        initialized = true
    }

    /** Sin red con internet MapLibre no pide nada: el aviso de sin conexión lo sabe por Android (S009/RF-15). */
    private fun watchInternet() {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
        monitor.onInternetAvailable(connectivity.activeNetwork != null)
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    monitor.onInternetAvailable(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))

                override fun onLost(network: Network) = monitor.onInternetAvailable(false)
            },
        )
    }
}

/** Acceso al entorno del mapa desde Compose, fuera de los ViewModel (ADR-9). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MapEntryPoint {
    fun mapEnvironment(): MapEnvironment
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
    LaunchedEffect(mapView) { mapView.getMapAsync { map -> onReady(MapLibreMapController(map, mapView, environment.httpClient)) } }
}

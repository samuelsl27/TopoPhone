package es.topophone.app.ui.map

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Interceptor

/**
 * Si el mapa está sin conexión (S009/RF-15). Hay dos señales:
 * - sin una red con internet en el móvil: MapLibre ni siquiera lanza las peticiones;
 * - con red, una descarga que falla por la conexión, hasta que llega cualquier respuesta. Cancelar una
 *   petición (el Catastro al mover el mapa) no cuenta.
 */
@Singleton
class NetworkFailureMonitor @Inject constructor() {
    private var failing = false
    private var internet = true
    private val state = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = state

    val interceptor = Interceptor { chain ->
        try {
            chain.proceed(chain.request()).also { markSuccess() }
        } catch (error: IOException) {
            if (error.isConnectionFailure()) markFailure()
            throw error
        }
    }

    @Synchronized
    fun markFailure() {
        failing = true
        publish()
    }

    @Synchronized
    fun markSuccess() {
        failing = false
        publish()
    }

    /** Hay, o no, una red con internet en el móvil (lo dice Android). */
    @Synchronized
    fun onInternetAvailable(available: Boolean) {
        internet = available
        publish()
    }

    private fun publish() {
        state.value = failing || !internet
    }

    private fun IOException.isConnectionFailure() =
        this is UnknownHostException || this is ConnectException || this is SocketTimeoutException || this is NoRouteToHostException
}

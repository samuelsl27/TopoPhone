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
 * Si las descargas del mapa están fallando por falta de conexión (S009/RF-15): un fallo de conexión lo marca
 * y cualquier respuesta lo quita. Cancelar una petición (el Catastro al mover el mapa) no cuenta.
 */
@Singleton
class NetworkFailureMonitor @Inject constructor() {
    private val failing = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = failing

    val interceptor = Interceptor { chain ->
        try {
            chain.proceed(chain.request()).also { markSuccess() }
        } catch (error: IOException) {
            if (error.isConnectionFailure()) markFailure()
            throw error
        }
    }

    fun markFailure() {
        failing.value = true
    }

    fun markSuccess() {
        failing.value = false
    }

    private fun IOException.isConnectionFailure() =
        this is UnknownHostException || this is ConnectException || this is SocketTimeoutException || this is NoRouteToHostException
}

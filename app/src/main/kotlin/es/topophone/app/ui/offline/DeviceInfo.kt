package es.topophone.app.ui.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** El espacio libre del móvil en el almacenamiento privado de la app (S037/RF-12, RNF-T5). */
interface StorageInfo {
    fun freeBytes(): Long
}

/** La red con la que se descargaría (S037/RF-13, RF-14). */
interface NetworkInfo {
    fun kind(): NetworkKind
}

/** Qué red es, con lo que dice Android de ella. Una red sin internet comprobado no cuenta: la Wi-Fi del receptor no tiene salida (RNF-T4). */
object NetworkKinds {
    fun of(hasNetwork: Boolean, hasInternet: Boolean, validated: Boolean, cellular: Boolean): NetworkKind = when {
        !hasNetwork || !hasInternet || !validated -> NetworkKind.NONE
        cellular -> NetworkKind.MOBILE
        else -> NetworkKind.UNMETERED
    }
}

@Singleton
class AndroidStorageInfo @Inject constructor(@param:ApplicationContext private val context: Context) : StorageInfo {
    override fun freeBytes(): Long = StatFs(context.filesDir.path).availableBytes
}

/** La red por omisión de Android: la que usa el tráfico normal, que no es la Wi-Fi del receptor cuando esa no sale a internet. */
@Singleton
class AndroidNetworkInfo @Inject constructor(@param:ApplicationContext private val context: Context) : NetworkInfo {
    override fun kind(): NetworkKind {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return NetworkKind.NONE
        val network = connectivity.activeNetwork
        val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
        return NetworkKinds.of(
            hasNetwork = network != null && capabilities != null,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            cellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true,
        )
    }
}

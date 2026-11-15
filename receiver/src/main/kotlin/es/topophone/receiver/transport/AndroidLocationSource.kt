package es.topophone.receiver.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper

/**
 * Las posiciones del GNSS del móvil según el sistema (S022/RF-5, plan D8): el proveedor `gps` de [LocationManager] a 1 Hz, sin
 * Google Play Services (la app no tiene nube). Android da la altura sobre el elipsoide WGS84 y la precisión al 68 %.
 */
class AndroidLocationSource(private val context: Context) : LocationSource {

    private val manager: LocationManager? get() = context.getSystemService(LocationManager::class.java)

    private var listener: LocationListener? = null

    override fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun isEnabled(): Boolean = runCatching { manager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)

    @SuppressLint("MissingPermission") // comprobado con hasPermission()
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun start(onFix: (LocationFix) -> Unit) {
        stop()
        val manager = manager ?: return
        val created = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.toFix())

            override fun onProviderEnabled(provider: String) = Unit

            override fun onProviderDisabled(provider: String) = Unit

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        listener = created
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, UPDATE_PERIOD_MS, 0f, created, Looper.getMainLooper())
    }

    override fun stop() {
        listener?.let { runCatching { manager?.removeUpdates(it) } }
        listener = null
    }

    @Suppress("DEPRECATION")
    private fun Location.toFix() = LocationFix(
        latitudeDeg = latitude,
        longitudeDeg = longitude,
        ellipsoidalHeightM = if (hasAltitude()) altitude else null,
        horizontalAccuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
        verticalAccuracyM = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasVerticalAccuracy()) {
            verticalAccuracyMeters.toDouble()
        } else {
            null
        },
        timeMillis = time,
        mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) isMock else isFromMockProvider,
        satellites = extras?.getInt(SATELLITES_EXTRA)?.takeIf { it > 0 },
    )

    private companion object {
        const val UPDATE_PERIOD_MS = 1000L
        const val SATELLITES_EXTRA = "satellites"
    }
}

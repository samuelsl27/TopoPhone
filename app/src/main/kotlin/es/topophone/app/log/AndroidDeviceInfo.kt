package es.topophone.app.log

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import es.topophone.app.BuildConfig
import es.topophone.data.receiver.ReceiverPreferences
import kotlinx.coroutines.flow.first

/** Datos de la app y del móvil en este momento, para el informe de sistema (S014/RF-13). */
class AndroidDeviceInfo(private val context: Context, private val receiver: ReceiverPreferences) {

    suspend fun read(): DeviceInfo = DeviceInfo(
        appVersion = BuildConfig.VERSION_NAME,
        gitCommit = BuildConfig.GIT_COMMIT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        freeBytes = StatFs(context.filesDir.path).availableBytes,
        permissions = permissions().associate { it.substringAfterLast('.') to granted(it) },
        batteryExempt = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
        lastReceiver = receiver.lastReceiver.first()?.name,
    )

    /** Los permisos que pide la app en esta versión de Android (RNF-T1). */
    private fun permissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

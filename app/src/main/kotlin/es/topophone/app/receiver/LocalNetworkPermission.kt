package es.topophone.app.receiver

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/** El permiso del sistema para hablar con dispositivos de la red local, como un receptor Wi-Fi. */
const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/**
 * Permiso de red local, que Android exige desde la versión 17 (API 37) para abrir conexiones con dispositivos de la red Wi-Fi (S021/RF-6).
 * Solo se pide al conectar con un receptor Wi-Fi: con Bluetooth no hace falta y el resto de la app no lo usa.
 */
interface LocalNetworkPermission {
    /** Antes de Android 17 no hay nada que pedir. */
    val required: Boolean

    fun isGranted(): Boolean

    companion object {
        /** Android 16 o anterior: no hay nada que pedir. */
        val NotRequired: LocalNetworkPermission = object : LocalNetworkPermission {
            override val required = false

            override fun isGranted() = true
        }
    }
}

class AndroidLocalNetworkPermission(private val context: Context) : LocalNetworkPermission {
    override val required = Build.VERSION.SDK_INT >= LOCAL_NETWORK_SDK

    override fun isGranted(): Boolean =
        !required || ContextCompat.checkSelfPermission(context, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /** La versión de Android que introduce el permiso (API 37). */
        const val LOCAL_NETWORK_SDK = 37
    }
}

/** Ajustes de la app, donde se concede el permiso tras denegarlo (S021/RF-6). */
fun localNetworkSettingsIntent(packageName: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

package es.topophone.app.receiver

import android.content.Context
import es.topophone.app.R

/**
 * Los nombres de las dos fuentes que trae la app, tal como salen en la cabecera, la notificación y el registro (S022/RF-1, RF-12).
 * Son textos de usuario: viven en `strings.xml`, no en el código (RNF-T7).
 */
interface SourceLabels {
    fun phone(): String

    fun virtual(): String
}

/** Los nombres de las fuentes con los recursos de la app, en el idioma del móvil. */
class ResourceSourceLabels(private val context: Context) : SourceLabels {
    override fun phone(): String = context.getString(R.string.phone_gnss_name)

    override fun virtual(): String = context.getString(R.string.virtual_receiver_name)
}

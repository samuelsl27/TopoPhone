package es.topophone.app.ui.receiver

import androidx.annotation.StringRes
import es.topophone.app.R
import es.topophone.core.model.point.PositionSource

/** La fuente es el GNSS del móvil, con o sin ubicación simulada por otra app (S022/RF-5, RF-7). */
internal fun PositionSource.isPhone(): Boolean = this == PositionSource.PHONE || this == PositionSource.PHONE_MOCK

/**
 * Por qué esta fuente no usa correcciones NTRIP, o null si las usa (S022/RF-9, RF-18). El GNSS del móvil y el receptor virtual no
 * ofrecen NTRIP: se dice el motivo en lugar del estado de la conexión.
 */
@StringRes
internal fun PositionSource.ntripMotive(): Int? = when {
    isPhone() -> R.string.ntrip_unavailable_phone
    this == PositionSource.VIRTUAL -> R.string.ntrip_unavailable_virtual
    else -> null
}

/** El GNSS del móvil no da inclinación (S022/RF-9); el receptor virtual sí (escenario «Inclinación con el movimiento en ocho»). */
internal fun PositionSource.supportsTilt(): Boolean = !isPhone()

/** Un receptor de verdad, por Bluetooth o Wi-Fi: no el móvil, el virtual ni una reproducción (S043/RF-22, RF-23). */
internal fun PositionSource.isRealReceiver(): Boolean = this == PositionSource.RECEIVER

package es.topophone.app.receiver

import es.topophone.receiver.transport.LocationFix
import es.topophone.receiver.transport.LocationSource

/** Sin acceso a la ubicación del sistema: por defecto en los sitios que no usan el GNSS del móvil (S022). */
internal object NoLocationSource : LocationSource {
    override fun hasPermission() = false

    override fun isEnabled() = false

    override fun start(onFix: (LocationFix) -> Unit) = Unit

    override fun stop() = Unit
}

package es.topophone.receiver.experimental

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel

/**
 * El rastro del módulo en el log de la app (S044/RF-24; plan D12): cada lectura de configuración (cuántos ajustes, nunca sus valores) y
 * cada escritura (valor anterior, enviado, leído después y resultado). La hora y el formato los pone el log (S014); aquí solo el texto.
 * Los valores que llegan son una altura o el estado de los logs: ninguna clave oculta pasa por aquí (RNF-T11).
 */
class ReachAudit(private val log: AppLog) : ReachTrail {

    override fun configRead(settingCount: Int) {
        log.record(LogLevel.INFO, LogCategory.RECEIVER, "$PREFIX configuracion leida, $settingCount ajustes")
    }

    override fun write(operation: String, before: String?, sent: String, after: String?, result: String) {
        val level = if (result == CONFIRMED) LogLevel.INFO else LogLevel.WARNING
        val message = "$PREFIX $operation: antes ${before ?: UNKNOWN}, enviado $sent, despues ${after ?: UNKNOWN}, resultado $result"
        log.record(level, LogCategory.RECEIVER, message)
    }

    private companion object {
        const val PREFIX = "Experimental:"
        const val UNKNOWN = "desconocido"
        const val CONFIRMED = "confirmado"
    }
}

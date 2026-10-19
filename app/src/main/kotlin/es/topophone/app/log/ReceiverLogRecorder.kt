package es.topophone.app.log

import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.model.log.LogLevel
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.session.ConnectFailure
import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.NtripState
import es.topophone.receiver.session.ReceiverSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Una anotación pendiente de pasar al log. */
data class LogEntry(val level: LogLevel, val category: LogCategory, val message: String)

/**
 * Anota lo que pasa con el receptor y la NTRIP mirando el estado de la sesión desde fuera (S014/RF-1, RF-2, RF-5; plan, A4):
 * conexiones, reproducciones, NTRIP y sus avisos. El estado no lleva el usuario ni la contraseña del caster, así que las
 * anotaciones tampoco (S014/RF-7).
 */
class ReceiverLogRecorder(private val states: Flow<ReceiverSnapshot>, private val log: AppLog, private val scope: CoroutineScope) {
    private val started = AtomicBoolean(false)

    /** Una vez por proceso. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            var previous = ReceiverSnapshot()
            states.collect { current ->
                entries(previous, current).forEach { log.record(it.level, it.category, it.message) }
                previous = current
            }
        }
    }

    companion object {
        /** Las anotaciones del paso de [previous] a [current]. */
        fun entries(previous: ReceiverSnapshot, current: ReceiverSnapshot): List<LogEntry> = buildList {
            connection(previous.connection, current.connection)?.let(::add)
            if (previous.replay == null && current.replay != null) {
                val replay = current.replay!!
                add(
                    LogEntry(
                        LogLevel.INFO,
                        LogCategory.RECEIVER,
                        "Reproducción: ${replay.name}, velocidad ×${replay.speed.factor}${if (replay.loop) ", en bucle" else ""}",
                    ),
                )
            }
            if (previous.replay != null &&
                current.replay == null
            ) {
                add(LogEntry(LogLevel.INFO, LogCategory.RECEIVER, "Reproducción detenida"))
            }
            if (!previous.nmeaMissing && current.nmeaMissing) {
                add(
                    LogEntry(
                        LogLevel.WARNING,
                        LogCategory.RECEIVER,
                        "10 s sin frases NMEA válidas: la salida de posición del receptor puede estar desactivada",
                    ),
                )
            }
            if (!previous.recordingStoppedLowSpace && current.recordingStoppedLowSpace) {
                add(LogEntry(LogLevel.WARNING, LogCategory.RECEIVER, "Sesión sin grabar por falta de espacio libre"))
            }
            ntrip(previous.ntrip, current.ntrip)?.let(::add)
            if (!previous.correctionsAlarm && current.correctionsAlarm) {
                add(LogEntry(LogLevel.WARNING, LogCategory.NTRIP, "Las correcciones tienen más edad que el umbral de la alarma"))
            }
            if (previous.correctionsAlarm && !current.correctionsAlarm) {
                add(LogEntry(LogLevel.INFO, LogCategory.NTRIP, "Las correcciones vuelven a estar al día"))
            }
        }

        private fun connection(previous: ConnectionState, current: ConnectionState): LogEntry? {
            if (previous == current) return null
            fun info(text: String) = LogEntry(LogLevel.INFO, LogCategory.RECEIVER, text)
            fun warning(text: String) = LogEntry(LogLevel.WARNING, LogCategory.RECEIVER, text)
            return when (current) {
                is ConnectionState.Connecting -> if (previous is ConnectionState.Connecting) {
                    null
                } else {
                    info(
                        "Conectando: ${current.description}",
                    )
                }
                is ConnectionState.Receiving -> if (previous is ConnectionState.Receiving) {
                    null
                } else {
                    info(
                        "Conectado: ${current.description}",
                    )
                }
                is ConnectionState.NoData -> if (previous is ConnectionState.NoData) {
                    null
                } else {
                    warning(
                        "Sin datos del receptor desde hace más de 2 s: ${current.description}",
                    )
                }
                is ConnectionState.Lost -> if (previous is ConnectionState.Lost) null else warning("Enlace perdido: ${current.description}")
                is ConnectionState.Reconnecting -> {
                    val before = previous as? ConnectionState.Reconnecting
                    if (before != null && before.attempt == current.attempt && before.bluetoothOff == current.bluetoothOff) {
                        null
                    } else {
                        warning(
                            "Reconectando, intento ${current.attempt}: ${current.description}${if (current.bluetoothOff) " (Bluetooth del móvil apagado)" else ""}",
                        )
                    }
                }
                is ConnectionState.Failed -> LogEntry(
                    LogLevel.ERROR,
                    LogCategory.RECEIVER,
                    "No se pudo conectar: ${current.failure.cause()}",
                )
                is ConnectionState.ReplayFinished -> info("Reproducción terminada: ${current.description}")
                ConnectionState.Disconnected -> info("Desconectado")
            }
        }

        private fun ntrip(previous: NtripState, current: NtripState): LogEntry? {
            if (previous::class == current::class && current !is NtripState.Reconnecting && current !is NtripState.Stopped) return null
            if (previous == current) return null
            fun info(text: String) = LogEntry(LogLevel.INFO, LogCategory.NTRIP, text)
            return when (current) {
                NtripState.Connecting -> info("Conectando al caster")
                is NtripState.Streaming -> info("Recibiendo correcciones")
                is NtripState.Stopped -> LogEntry(LogLevel.ERROR, LogCategory.NTRIP, "Cerrada: ${current.failure.cause()}")
                is NtripState.Reconnecting -> {
                    val before = previous as? NtripState.Reconnecting
                    if (before?.attempt == current.attempt) {
                        null
                    } else {
                        LogEntry(LogLevel.WARNING, LogCategory.NTRIP, "Cortada (${current.failure.cause()}); reintento ${current.attempt}")
                    }
                }
                NtripState.Idle -> info("Cerrada")
                NtripState.Paused -> info("En pausa mientras se reconecta el receptor")
                NtripState.ReceiverNotConnected -> LogEntry(
                    LogLevel.WARNING,
                    LogCategory.NTRIP,
                    "No se puede conectar sin un receptor conectado",
                )
                NtripState.UnavailableInReplay -> LogEntry(LogLevel.WARNING, LogCategory.NTRIP, "Una reproducción no admite correcciones")
            }
        }

        private fun ConnectFailure.cause(): String = when (this) {
            ConnectFailure.BUSY -> "el receptor rechazó la conexión; puede haber otro dispositivo conectado a él por Bluetooth"
            ConnectFailure.TIMEOUT -> "tiempo agotado"
            ConnectFailure.ERROR -> "error de conexión"
        }

        private fun NtripFailure.cause(): String = when (this) {
            NtripFailure.UNAUTHORIZED -> "el caster rechazó el usuario o la contraseña"
            NtripFailure.MOUNTPOINT_NOT_FOUND -> "el punto de montaje no existe"
            NtripFailure.UNREACHABLE -> "no se llega al caster"
            NtripFailure.NO_DATA -> "el caster no envía datos"
            NtripFailure.CONNECTION_LOST -> "conexión perdida"
            NtripFailure.PROTOCOL -> "respuesta no válida del caster"
            NtripFailure.TLS_INVALID -> "certificado TLS no válido"
        }
    }
}

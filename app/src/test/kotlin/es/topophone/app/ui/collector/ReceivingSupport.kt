package es.topophone.app.ui.collector

import es.topophone.receiver.session.ConnectionState
import es.topophone.receiver.session.ReceiverSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** La hora UTC, en ms del día, de la época 1 que mandan los `receiving` de estos tests: 10:15:31. */
internal const val RECEIVING_LAST_EPOCH_UTC_MS = 36_931_000

/**
 * Espera a que la sesión haya cerrado la época [utcMs] (o una posterior) y a que el recolector la haya recibido. Una época sigue abierta hasta que
 * llega la siguiente o pasan 150 ms de silencio, y el recolector la recibe un poco después que el estado de la sesión, en otro
 * hilo: si el test empezara a medir antes, en un equipo lento esa época entraría en la medida y cambiaría la cuenta.
 */
internal fun awaitEpoch(session: ReceiverSession, vm: CollectorViewModel, utcMs: Int) = runBlocking {
    withTimeout(5_000) {
        session.state.first { (it.epoch?.utcMillisOfDay ?: -1) >= utcMs }
        while ((vm.lastEpochUtcMs ?: -1) < utcMs) delay(5)
    }
}

/** Tras las épocas 0 y 1 de los `receiving`: la sesión recibe y el recolector ya tiene la 1, que no entrará en la medida siguiente. */
internal fun awaitReceiving(session: ReceiverSession, vm: CollectorViewModel) {
    awaitEpoch(session, vm, RECEIVING_LAST_EPOCH_UTC_MS)
    runBlocking { withTimeout(5_000) { session.state.first { it.connection is ConnectionState.Receiving } } }
}

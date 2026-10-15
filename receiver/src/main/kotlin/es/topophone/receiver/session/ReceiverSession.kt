package es.topophone.receiver.session

import es.topophone.core.gnss.nmea.Epoch
import es.topophone.core.gnss.nmea.EpochAssembler
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.GgaParser
import es.topophone.core.gnss.nmea.GstAccuracy
import es.topophone.core.gnss.nmea.GstParser
import es.topophone.core.gnss.nmea.NmeaFramer
import es.topophone.core.gnss.nmea.NmeaSentence
import es.topophone.core.ntrip.NtripConfig
import es.topophone.core.ntrip.NtripConnector
import es.topophone.core.ntrip.NtripFailure
import es.topophone.receiver.transport.ReceiverTransport
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Estado de la conexión con el receptor (S002/RF-5 a RF-10). */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connecting(val description: String) : ConnectionState

    data class Receiving(val description: String) : ConnectionState

    data class NoData(val description: String) : ConnectionState

    data class Lost(val description: String) : ConnectionState

    data class Failed(val failure: ConnectFailure) : ConnectionState
}

enum class ConnectFailure { TIMEOUT, ERROR }

/** Estado de las correcciones NTRIP (S002/RF-19 a RF-25, RF-28). */
sealed interface NtripState {
    data object Idle : NtripState

    data object Connecting : NtripState

    data class Streaming(val bytesReceived: Long, val lastCorrectionAtMs: Long?) : NtripState

    data class Stopped(val failure: NtripFailure) : NtripState

    data object ReceiverNotConnected : NtripState
}

/**
 * Lo que la interfaz necesita saber de la sesión en cada momento. La GGA y la GST se publican al
 * llegar (latencia de S002); [epoch] es la última época completa (S003/RF-8).
 */
data class ReceiverSnapshot(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val gga: GgaFix? = null,
    val gst: GstAccuracy? = null,
    val ntrip: NtripState = NtripState.Idle,
    val epoch: Epoch? = null,
    /** Satélites en vista de la última época que trajo GSV (a 1 Hz frente a épocas a 5 Hz) (S003/RF-15). */
    val satellitesInView: Int? = null,
    /** Se está grabando la sesión (S003/RF-20). */
    val recording: Boolean = false,
)

/** Registro de los datos brutos recibidos del receptor (S002/RF-42). */
fun interface SessionRecorder {
    fun start(description: String): SessionWriter?

    companion object {
        val NONE = SessionRecorder { null }
    }
}

interface SessionWriter {
    /** Bytes recibidos del receptor, tal como llegan (S002/RF-42). */
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Correcciones enviadas al receptor, tal como se envían (S003/RF-24). */
    fun writeCorrections(bytes: ByteArray)

    fun flush()

    fun close()
}

/**
 * Grabación de la sesión en curso. Un fallo al escribir (disco lleno…) detiene la grabación, pero
 * no la conexión con el receptor: no debe confundirse con un corte del enlace.
 */
private class Recording(private val writer: SessionWriter, private val onStopped: () -> Unit) {
    @Volatile private var active = true

    fun write(bytes: ByteArray, offset: Int, length: Int) = guard { writer.write(bytes, offset, length) }

    fun writeCorrections(bytes: ByteArray) = guard { writer.writeCorrections(bytes) }

    fun flush() = guard { writer.flush() }

    fun close() {
        if (active) runCatching { writer.close() }
        active = false
    }

    private inline fun guard(block: () -> Unit) {
        if (!active) return
        try {
            block()
        } catch (_: IOException) {
            close()
            onStopped()
        }
    }
}

/**
 * Sesión con el receptor (ADR-11): una conexión, un lector, una cola única de escritura hacia el
 * receptor y, opcionalmente, una conexión NTRIP que reenvía correcciones por esa cola.
 */
class ReceiverSession(
    private val scope: CoroutineScope,
    private val ntripConnector: NtripConnector,
    private val recorder: SessionRecorder = SessionRecorder.NONE,
    private val connectTimeoutMs: Long = 10_000,
    private val noDataTimeoutMs: Long = NO_DATA_TIMEOUT_MS,
    private val accuracyStaleMs: Long = 3_000,
    private val watchdogPeriodMs: Long = TICK_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val mutableState = MutableStateFlow(ReceiverSnapshot())
    val state: StateFlow<ReceiverSnapshot> = mutableState.asStateFlow()

    private val lock = Any()
    private var connectionJob: Job? = null
    private var ntripJob: Job? = null
    private var transport: ReceiverTransport? = null
    private var writes: Channel<ByteArray>? = null

    @Volatile private var lastEpochAtMs = 0L

    /** Lo usan el lector y el vigilante: siempre bajo [assemblerLock]. */
    private val assemblerLock = Any()
    private var assembler = EpochAssembler()

    @Volatile private var lastGstAtMs = 0L

    /** Abre la conexión con el receptor (S002/RF-5). */
    fun connect(newTransport: ReceiverTransport) {
        disconnect()
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        synchronized(lock) {
            transport = newTransport
            writes = channel
        }
        val description = newTransport.description
        mutableState.value = ReceiverSnapshot(connection = ConnectionState.Connecting(description))
        connectionJob = scope.launch {
            open(newTransport)?.let { failure ->
                mutableState.update { it.copy(connection = ConnectionState.Failed(failure)) } // S002/RF-7
                return@launch
            }
            synchronized(assemblerLock) { assembler = EpochAssembler() }
            lastEpochAtMs = clock()
            val recording = recorder.start(description)?.let { writer ->
                Recording(writer) { mutableState.update { it.copy(recording = false) } }
            }
            mutableState.update { it.copy(recording = recording != null) } // S003/RF-20
            try {
                coroutineScope {
                    launch(Dispatchers.IO) { drainWrites(newTransport, channel, recording) }
                    launch { watchdog(description) }
                    read(newTransport, description, recording)
                    if (currentCoroutineContext().isActive) {
                        ntripJob?.cancel()
                        mutableState.update {
                            it.copy(connection = ConnectionState.Lost(description), ntrip = NtripState.Idle, recording = false)
                        } // S002/RF-9
                    }
                    coroutineContext.cancelChildren()
                }
            } finally {
                recording?.close()
            }
        }
    }

    /** Cierra el NTRIP y la conexión con el receptor (S002/RF-10). */
    fun disconnect() {
        ntripJob?.cancel()
        ntripJob = null
        connectionJob?.cancel()
        connectionJob = null
        synchronized(lock) {
            runCatching { transport?.close() }
            transport = null
            writes?.close()
            writes = null
        }
        mutableState.value = ReceiverSnapshot()
    }

    /** Conecta con el caster y reenvía sus correcciones al receptor (S002/RF-19, RF-28). */
    fun startNtrip(config: NtripConfig) {
        val channel = synchronized(lock) { writes }
        val connection = mutableState.value.connection
        if (channel == null || (connection !is ConnectionState.Receiving && connection !is ConnectionState.NoData)) {
            mutableState.update { it.copy(ntrip = NtripState.ReceiverNotConnected) }
            return
        }
        ntripJob?.cancel()
        mutableState.update { it.copy(ntrip = NtripState.Connecting) }
        ntripJob = scope.launch {
            var bytes = 0L
            val failure = ntripConnector.run(
                config,
                latestGga = { mutableState.value.gga?.rawSentence },
                onConnected = { mutableState.update { it.copy(ntrip = NtripState.Streaming(0, null)) } },
            ) { corrections ->
                channel.send(corrections)
                bytes += corrections.size
                val now = clock()
                mutableState.update { it.copy(ntrip = NtripState.Streaming(bytes, now)) } // S002/RF-25
            }
            mutableState.update { it.copy(ntrip = NtripState.Stopped(failure)) }
        }
    }

    fun stopNtrip() {
        ntripJob?.cancel()
        ntripJob = null
        mutableState.update { it.copy(ntrip = NtripState.Idle) }
    }

    /** Abre el transporte con plazo; si vence, lo cierra para desbloquear la conexión (S002/RF-7). */
    private suspend fun open(transport: ReceiverTransport): ConnectFailure? {
        val opening = scope.async(Dispatchers.IO) { runCatching { transport.open() } }
        val result = withTimeoutOrNull(connectTimeoutMs) { opening.await() }
        return when {
            result == null -> {
                runCatching { transport.close() }
                opening.cancel()
                ConnectFailure.TIMEOUT
            }
            result.isFailure -> {
                runCatching { transport.close() }
                ConnectFailure.ERROR
            }
            else -> null
        }
    }

    private suspend fun read(transport: ReceiverTransport, description: String, recording: Recording?) = withContext(Dispatchers.IO) {
        val framer = NmeaFramer()
        val buffer = ByteArray(READ_BUFFER)
        try {
            val input = transport.input
            while (isActive) {
                val n = input.read(buffer)
                if (n < 0) break
                recording?.write(buffer, 0, n) // S002/RF-42
                framer.feed(buffer, 0, n).forEach { onSentence(it, description) }
            }
        } catch (_: IOException) {
            // Enlace cortado o cerrado al desconectar: lo decide connect() según si sigue activa.
        }
    }

    private fun onSentence(sentence: NmeaSentence, description: String) {
        val now = clock()
        val gga = if (sentence.type == "GGA") GgaParser.parse(sentence) else null
        val gst = if (sentence.type == "GST") GstParser.parse(sentence) else null
        if (gst != null) lastGstAtMs = now
        mutableState.update { current ->
            // S002/RF-6: la primera frase válida da "Recibiendo"; de "Sin datos" se sale con una época (S003/RF-12).
            val connection = if (current.connection is ConnectionState.Connecting) {
                ConnectionState.Receiving(
                    description,
                )
            } else {
                current.connection
            }
            current.copy(connection = connection, gga = gga ?: current.gga, gst = gst ?: current.gst)
        }
        synchronized(assemblerLock) { assembler.feed(sentence, now) }?.let { onEpoch(it, description) }
    }

    /** Época completa (S003/RF-8): se publica y, si estaba en "Sin datos", se vuelve a "Recibiendo". */
    private fun onEpoch(epoch: Epoch, description: String) {
        lastEpochAtMs = epoch.completedAtMs
        mutableState.update { current ->
            val connection = if (current.connection is ConnectionState.NoData) {
                ConnectionState.Receiving(
                    description,
                )
            } else {
                current.connection
            }
            current.copy(connection = connection, epoch = epoch, satellitesInView = epoch.satellitesInView ?: current.satellitesInView)
        }
    }

    /**
     * Completa la época tras el silencio (S003/RF-9); sin época durante el plazo → Sin datos
     * (S002/RF-8, S003/RF-12); GST antigua → sin precisión (S002/RF-17).
     */
    private suspend fun watchdog(description: String) {
        while (currentCoroutineContext().isActive) {
            delay(watchdogPeriodMs)
            val now = clock()
            synchronized(assemblerLock) { assembler.tick(now) }?.let { onEpoch(it, description) }
            mutableState.update { current ->
                var next = current
                val waiting = current.connection is ConnectionState.Receiving || current.connection is ConnectionState.Connecting
                if (waiting && now - lastEpochAtMs > noDataTimeoutMs) next = next.copy(connection = ConnectionState.NoData(description))
                if (current.gst != null && now - lastGstAtMs > accuracyStaleMs) next = next.copy(gst = null)
                next
            }
        }
    }

    /** Cola única de escritura hacia el receptor (ADR-11). */
    private suspend fun drainWrites(transport: ReceiverTransport, channel: Channel<ByteArray>, recording: Recording?) {
        try {
            val output = transport.output
            for (bytes in channel) {
                output.write(bytes)
                output.flush()
                recording?.writeCorrections(bytes) // S003/RF-24
            }
        } catch (_: IOException) {
            // El lector detectará el corte.
        }
    }

    companion object {
        /** RNF-T3: más de 2 s sin época → "Sin datos" (S003/RF-12). */
        const val NO_DATA_TIMEOUT_MS = 2_000L

        /** Periodo del vigilante: resolución con la que se detectan los 150 ms de silencio (S003/RF-9). */
        const val TICK_MS = 25L
        private const val READ_BUFFER = 4096
    }
}

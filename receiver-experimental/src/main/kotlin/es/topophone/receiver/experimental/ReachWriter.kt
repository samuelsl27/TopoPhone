package es.topophone.receiver.experimental

import kotlin.math.abs
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Por qué una escritura ni siquiera se intentó (no se escribió nada). */
enum class WriteFailure { NO_RESPONSE, INCOMPATIBLE, INVALID_VALUE, NO_LIVE }

/**
 * El resultado de una escritura (S044/RF-19, RF-21; plan «Contratos»): [Confirmed] con lo que el receptor dice tener al releer;
 * [NotConfirmed] con lo que quedó (`null` si no se pudo releer) y si el envío mismo falló; o [Failed] si no se llegó a escribir.
 */
sealed interface WriteResult<out T> {
    data class Confirmed<T>(val value: T) : WriteResult<T>

    data class NotConfirmed<T>(val read: T?, val requestFailed: Boolean) : WriteResult<T>

    data class Failed(val cause: WriteFailure) : WriteResult<Nothing>
}

/**
 * Lo que el escritor anota de cada lectura de configuración y de cada escritura (S044/RF-24; plan D12). La implementación de la app
 * lo lleva al log (T11); sin ella, [NONE]. Nunca recibe valores de claves ocultas.
 */
interface ReachTrail {
    fun configRead(settingCount: Int)

    fun write(operation: String, before: String?, sent: String, after: String?, result: String)

    companion object {
        val NONE: ReachTrail = object : ReachTrail {
            override fun configRead(settingCount: Int) = Unit

            override fun write(operation: String, before: String?, sent: String, after: String?, result: String) = Unit
        }
    }
}

/**
 * Las dos únicas escrituras que el módulo hace en el receptor: la altura de antena y el inicio o parada de los logs (S044/RF-22).
 * Nunca reintenta (RF-21). El que llama ya pidió confirmación y solo lo hace con la información básica válida (RF-15).
 */
class ReachWriter(private val client: ReachHttpClient, private val trail: ReachTrail = ReachTrail.NONE) {

    /**
     * Lee `/configuration`, copia el objeto `device` con la nueva altura y lo envía completo (el resto de los ajustes, con el valor que el
     * receptor tenía), vuelve a leer y compara con 0,5 mm de tolerancia (plan A2). La unidad se supone metros (plan D10).
     */
    suspend fun setAntennaHeight(valueM: Double): WriteResult<Double> {
        if (!valueM.isFinite()) return WriteResult.Failed(WriteFailure.INVALID_VALUE)
        val current = when (val read = readDevice()) {
            is DeviceRead.Found -> read.device
            is DeviceRead.Missing -> return WriteResult.Failed(read.cause)
        }
        val before = ReachJson.number(current, ANTENNA_HEIGHT) ?: return WriteResult.Failed(WriteFailure.INCOMPATIBLE)

        val sentBody = JsonObject(current + (ANTENNA_HEIGHT to JsonPrimitive(valueM))).toString()
        val posted = client.post("/configuration/device", sentBody) is ReachResult.Ok
        val after = (readDevice() as? DeviceRead.Found)?.let { ReachJson.number(it.device, ANTENNA_HEIGHT) }

        val result = when {
            posted && after != null && abs(after - valueM) <= TOLERANCE_M -> "confirmado"
            !posted -> "fallo del envio"
            after == null -> "sin relectura"
            else -> "no coincide"
        }
        trail.write(OPERATION, before.toString(), valueM.toString(), after?.toString(), result)
        return if (result == "confirmado") {
            WriteResult.Confirmed(after!!)
        } else {
            WriteResult.NotConfirmed(read = after, requestFailed = !posted)
        }
    }

    /**
     * Inicia o para todos los logs con `POST /configuration/logging/logs {"started": b}` y espera hasta [waitMs] al siguiente evento
     * `active_logs` del directo para comprobar el estado resultante (plan D11, A3). Sin directo no se envía nada: no habría forma de
     * mostrar el estado de antes ni de comprobar el de después (RF-20). Se suscribe **antes** de enviar para no perder el evento.
     */
    suspend fun setLogs(started: Boolean, live: LogsObserver, waitMs: Long = LOG_CONFIRM_WAIT_MS): WriteResult<Boolean> {
        if (!live.state.value.connected) return WriteResult.Failed(WriteFailure.NO_LIVE)
        val before = live.state.value.logsWriting
        val (posted, event) = coroutineScope {
            val next = async(start = CoroutineStart.UNDISPATCHED) { withTimeoutOrNull(waitMs) { live.logEvents.first() } }
            val ok = client.post("/configuration/logging/logs", """{"started":$started}""") is ReachResult.Ok
            if (!ok) next.cancel()
            ok to (if (ok) next.await() else null)
        }
        val seen = event ?: live.state.value.logsWriting
        val result = when {
            !posted -> "fallo del envio"
            seen == started -> "confirmado"
            event == null -> "sin confirmacion"
            else -> "no coincide"
        }
        trail.write("logs", before.logsText(), if (started) "iniciar" else "parar", seen.logsText(), result)
        return if (result == "confirmado") WriteResult.Confirmed(started) else WriteResult.NotConfirmed(seen, requestFailed = !posted)
    }

    private fun Boolean?.logsText(): String? = when (this) {
        true -> "escribiendo"
        false -> "parados"
        null -> null
    }

    private sealed interface DeviceRead {
        data class Found(val device: JsonObject) : DeviceRead

        data class Missing(val cause: WriteFailure) : DeviceRead
    }

    /** El objeto `configuration.device` leído, o por qué no se pudo. */
    private suspend fun readDevice(): DeviceRead {
        val body = (client.get("/configuration") as? ReachResult.Ok)?.value ?: return DeviceRead.Missing(WriteFailure.NO_RESPONSE)
        val device = ReachJson.objectAt(ReachJson.parse(body), "configuration", "device")
        return if (device == null) DeviceRead.Missing(WriteFailure.INCOMPATIBLE) else DeviceRead.Found(device)
    }

    private companion object {
        const val ANTENNA_HEIGHT = "antenna_height"
        const val OPERATION = "altura de antena"
        const val TOLERANCE_M = 0.0005

        /** Cuánto se espera al evento de logs que confirma la escritura (S044/RF-20). */
        const val LOG_CONFIRM_WAIT_MS = 3_000L
    }
}

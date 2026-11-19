package es.topophone.receiver.experimental

import javax.net.SocketFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * Un receptor de mentira con estado para los tests de escritura: guarda la configuración que se le manda y la devuelve en la lectura
 * siguiente. Cada comportamiento raro (no aplicar, apagarse a mitad…) se elige con una propiedad. Las respuestas parten de `fixtures/reach`.
 */
class FakeReachServer : Dispatcher() {
    val server = MockWebServer()

    /** Todas las peticiones recibidas, en orden, como `MÉTODO ruta`. */
    val requests = mutableListOf<String>()

    /** El cuerpo de cada `POST /configuration/device`. */
    val deviceBodies = mutableListOf<String>()

    /** El cuerpo de cada `POST /configuration/logging/logs`. */
    val logBodies = mutableListOf<String>()

    var configuration: JsonObject = Json.parseToJsonElement(ReachFixtures.text("configuration.json")) as JsonObject

    /** `false`: el POST llega pero el receptor no lo aplica. */
    var applyDeviceWrites = true
    var deviceWriteStatus = 200

    /** Cuántas lecturas de `/configuration` se contestan bien antes de dejar de contestar (receptor que se apaga a mitad). */
    var configurationReadsLeft = Int.MAX_VALUE

    /** Si no es `null`, el receptor guarda este valor de altura en vez del recibido. */
    var storedAntennaOverride: Double? = null

    var infoBody: String = ReachFixtures.text("info.json")
    var logsStarted = false
    var logWriteStatus = 200

    fun start(): FakeReachServer {
        server.dispatcher = this
        server.start()
        return this
    }

    fun stop() = server.shutdown()

    fun client(timeoutMs: Long = ReachHttpClient.CALL_TIMEOUT_MS) =
        ReachHttpClient(ReachEndpoint(server.hostName, server.port), SocketFactory.getDefault(), timeoutMs)

    private val configurationObject: JsonObject get() = configuration["configuration"] as JsonObject

    val device: JsonObject get() = configurationObject["device"] as JsonObject

    override fun dispatch(request: RecordedRequest): MockResponse {
        requests += "${request.method} ${request.path}"
        return when ("${request.method} ${request.path}") {
            "GET /info" -> MockResponse().setBody(infoBody)
            "GET /configuration" -> {
                if (configurationReadsLeft-- > 0) MockResponse().setBody(configuration.toString()) else MockResponse().setResponseCode(503)
            }
            "POST /configuration/device" -> {
                val body = request.body.readUtf8()
                deviceBodies += body
                if (applyDeviceWrites && deviceWriteStatus in 200..299) {
                    var newDevice = Json.parseToJsonElement(body) as JsonObject
                    storedAntennaOverride?.let { newDevice = JsonObject(newDevice + ("antenna_height" to JsonPrimitive(it))) }
                    configuration = JsonObject(
                        configuration + ("configuration" to JsonObject(configurationObject + ("device" to newDevice))),
                    )
                }
                MockResponse().setResponseCode(deviceWriteStatus).setBody("{}")
            }
            "POST /configuration/logging/logs" -> {
                val body = request.body.readUtf8()
                logBodies += body
                if (logWriteStatus in 200..299) logsStarted = body.contains("true")
                MockResponse().setResponseCode(logWriteStatus).setBody("{}")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }
}

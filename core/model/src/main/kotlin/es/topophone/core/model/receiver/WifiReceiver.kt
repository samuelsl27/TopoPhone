package es.topophone.core.model.receiver

/** Los valores iniciales del formulario de un receptor Wi-Fi: el hotspot del receptor y su salida de posición por TCP (S021/RF-2). */
object WifiReceiverDefaults {
    const val HOST = "192.168.42.1"
    const val POSITION_PORT = 9001
}

/**
 * Un receptor Wi-Fi guardado (S021): [host] es una IP o un nombre, [positionPort] el puerto del servidor TCP del receptor que emite el
 * NMEA y [correctionsPort] el que acepta RTCM3, o null si no tiene (S021/RF-12). El [id] es su identidad: no cambia al editar lo demás
 * (S021/RF-5).
 */
data class WifiReceiver(val id: String, val name: String, val host: String, val positionPort: Int, val correctionsPort: Int?) {
    /** La descripción con que se muestra y se graba la conexión: `nombre (dirección:puerto)`. */
    val description: String get() = "$name ($host:$positionPort)"
}

/**
 * La clave con que otras partes de la app guardan lo de cada receptor (último receptor, jalón y antena, modo de inclinación): la
 * dirección Bluetooth, `replay` o, para un receptor Wi-Fi, `wifi:<id>` (S021/RF-5, RF-18).
 */
object ReceiverKey {
    private const val WIFI_PREFIX = "wifi:"

    fun wifi(id: String): String = WIFI_PREFIX + id

    fun of(receiver: WifiReceiver): String = wifi(receiver.id)

    fun isWifi(key: String): Boolean = wifiId(key) != null

    /** La identidad de un receptor Wi-Fi, o null si la clave no es de uno (o no la lleva). */
    fun wifiId(key: String): String? = if (key.startsWith(WIFI_PREFIX)) key.removePrefix(WIFI_PREFIX).ifEmpty { null } else null
}

/** Lo escrito en el formulario, tal cual (S021/RF-2). */
data class WifiReceiverDraft(val name: String, val host: String, val positionPort: String, val correctionsPort: String) {
    companion object {
        fun initial() = WifiReceiverDraft("", WifiReceiverDefaults.HOST, WifiReceiverDefaults.POSITION_PORT.toString(), "")
    }
}

/** Lo que impide guardar el receptor (S021/RF-3). */
enum class WifiReceiverProblem { HOST_EMPTY, HOST_INVALID, POSITION_PORT_INVALID, CORRECTIONS_PORT_INVALID }

/** Lo que se avisa pero deja guardar: el fabricante reserva los puertos menores de 3000 (S021/RF-3). */
enum class WifiReceiverWarning { RESERVED_POSITION_PORT, RESERVED_CORRECTIONS_PORT }

/** El receptor ya interpretado, sin identidad todavía. */
data class WifiReceiverInput(val name: String, val host: String, val positionPort: Int, val correctionsPort: Int?)

sealed interface WifiReceiverValidation {
    data class Valid(val input: WifiReceiverInput, val warnings: Set<WifiReceiverWarning>) : WifiReceiverValidation

    data class Invalid(val problems: Set<WifiReceiverProblem>) : WifiReceiverValidation
}

/** Comprueba el formulario del receptor Wi-Fi (S021/RF-2, RF-3). */
object WifiReceiverValidator {
    private const val MIN_PORT = 1
    private const val MAX_PORT = 65_535
    private const val FIRST_FREE_PORT = 3_000
    private const val MAX_HOST_LENGTH = 253
    private const val MAX_LABEL_LENGTH = 63
    private const val IPV4_PARTS = 4
    private const val MAX_OCTET = 255
    private val LABEL = Regex("""[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?""")
    private val IPV6_BODY = Regex("""[0-9A-Fa-f:.]+""")
    private val DIGITS = Regex("""[0-9]+""")

    fun validate(draft: WifiReceiverDraft): WifiReceiverValidation {
        val host = draft.host.trim()
        val problems = mutableSetOf<WifiReceiverProblem>()
        when {
            host.isEmpty() -> problems += WifiReceiverProblem.HOST_EMPTY
            !isHost(host) -> problems += WifiReceiverProblem.HOST_INVALID
        }
        val position = port(draft.positionPort)
        if (position == null) problems += WifiReceiverProblem.POSITION_PORT_INVALID
        val correctionsText = draft.correctionsPort.trim()
        val corrections = if (correctionsText.isEmpty()) null else port(correctionsText)
        if (correctionsText.isNotEmpty() && corrections == null) problems += WifiReceiverProblem.CORRECTIONS_PORT_INVALID
        if (problems.isNotEmpty() || position == null) return WifiReceiverValidation.Invalid(problems)

        val warnings = buildSet {
            if (position < FIRST_FREE_PORT) add(WifiReceiverWarning.RESERVED_POSITION_PORT)
            if (corrections != null && corrections < FIRST_FREE_PORT) add(WifiReceiverWarning.RESERVED_CORRECTIONS_PORT)
        }
        val name = draft.name.trim().ifEmpty { "$host:$position" }
        return WifiReceiverValidation.Valid(WifiReceiverInput(name, host, position, corrections), warnings)
    }

    private fun port(text: String): Int? {
        val trimmed = text.trim()
        if (!DIGITS.matches(trimmed) || trimmed.length > 5) return null
        return trimmed.toInt().takeIf { it in MIN_PORT..MAX_PORT }
    }

    private fun isHost(host: String): Boolean = when {
        host.startsWith("[") -> host.endsWith("]") && host.length > 2 && isIpv6(host.substring(1, host.length - 1))
        host.length > MAX_HOST_LENGTH -> false
        else -> {
            val labels = host.split('.')
            if (labels.all { DIGITS.matches(it) }) isIpv4(labels) else labels.all { it.length <= MAX_LABEL_LENGTH && LABEL.matches(it) }
        }
    }

    private fun isIpv4(parts: List<String>) = parts.size == IPV4_PARTS && parts.all { it.length <= 3 && it.toInt() <= MAX_OCTET }

    private fun isIpv6(body: String) = IPV6_BODY.matches(body) && ':' in body && body.count { it == ':' } >= 2
}

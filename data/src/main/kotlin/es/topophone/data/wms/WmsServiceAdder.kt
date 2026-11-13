package es.topophone.data.wms

import es.topophone.core.formats.wms.WmsCapabilities
import es.topophone.core.formats.wms.WmsCapabilitiesException
import es.topophone.core.formats.wms.WmsCapabilitiesProblem
import es.topophone.core.formats.wms.WmsCapabilitiesReader
import es.topophone.core.formats.wms.WmsPresets
import es.topophone.core.formats.wms.WmsRequests
import es.topophone.core.model.wms.WmsAddress
import es.topophone.core.model.wms.WmsAddressProblem
import es.topophone.core.model.wms.WmsAddressResult
import es.topophone.core.model.wms.WmsLimits
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService
import java.io.IOException

/** Por qué no se pudo añadir o actualizar un servicio (S036/RF-6 a RF-9). */
enum class WmsAddProblem { NO_RESPONSE, TIMEOUT, HTTP_ERROR, NOT_CAPABILITIES, SERVICE_EXCEPTION, MALFORMED, TOO_LARGE, NO_WEB_MERCATOR }

/** Cómo acabó añadir un servicio (S036/RF-1, RF-14, RF-15). */
sealed interface WmsAddOutcome {
    data class Added(val service: WmsService) : WmsAddOutcome

    /** Ya había un servicio con esa dirección: no se pide ni se duplica y se abre el que hay (RF-15). */
    data class AlreadyThere(val service: WmsService) : WmsAddOutcome

    /** Lo escrito no es una dirección que se pueda usar (RF-10). */
    data class InvalidAddress(val problem: WmsAddressProblem) : WmsAddOutcome

    /** [detail] es el código HTTP o el mensaje de la excepción del servicio, si los hay (RF-8). */
    data class Failed(val problem: WmsAddProblem, val detail: String? = null) : WmsAddOutcome

    data object UnknownPreset : WmsAddOutcome
}

/** Cómo acabó actualizar un servicio (S036/RF-6). */
sealed interface WmsRefreshOutcome {
    /** [missing] son las capas de proyectos que el servicio ya no ofrece. */
    data class Refreshed(val missing: List<MissingProjectLayer>) : WmsRefreshOutcome

    data object NotFound : WmsRefreshOutcome

    /** El servicio queda como estaba. */
    data class Failed(val problem: WmsAddProblem, val detail: String? = null) : WmsRefreshOutcome
}

/**
 * Pide el GetCapabilities de un servicio, lo lee y lo guarda en la biblioteca (S036/RF-1, RF-2, RF-6, RF-8 a RF-10, RF-14, RF-15): primero en la
 * versión 1.3.0 y, si el servicio no la ofrece, en la 1.1.1. Una respuesta HTTP de error, una excepción del servicio o una página que no es de
 * capacidades prueba la otra versión; un fallo de red, un tiempo agotado, un XML dañado o un documento demasiado grande, no, porque la otra
 * versión daría lo mismo.
 */
class WmsServiceAdder(
    private val http: WmsHttp,
    private val library: WmsLibraryRepository,
    private val maxBytes: Long = WmsLimits.MAX_CAPABILITIES_BYTES,
) {
    suspend fun add(text: String, requestMode: WmsRequestMode = WmsRequestMode.TILES, name: String? = null): WmsAddOutcome {
        val address = when (val parsed = WmsAddress.parse(text)) {
            is WmsAddressResult.Invalid -> return WmsAddOutcome.InvalidAddress(parsed.problem)
            is WmsAddressResult.Valid -> parsed.address
        }
        return addAddress(address, requestMode, presetKey = null, name = name)
    }

    suspend fun addPreset(key: String): WmsAddOutcome {
        val preset = WmsPresets.byKey(key) ?: return WmsAddOutcome.UnknownPreset
        val address = (WmsAddress.parse(preset.address) as? WmsAddressResult.Valid)?.address ?: return WmsAddOutcome.UnknownPreset
        return addAddress(address, preset.requestMode, preset.key, preset.name)
    }

    /** Vuelve a leer las capas de [serviceId], empezando por la versión que declaró, y guarda lo nuevo (RF-6). */
    suspend fun refresh(serviceId: String): WmsRefreshOutcome {
        val service = library.service(serviceId) ?: return WmsRefreshOutcome.NotFound
        val address = (WmsAddress.parse(service.address) as? WmsAddressResult.Valid)?.address ?: return WmsRefreshOutcome.NotFound
        return when (val read = read(address, first = service.version)) {
            is Read.Failed -> WmsRefreshOutcome.Failed(read.problem, read.detail)
            is Read.Ok -> {
                if (!read.capabilities.hasAddableLayer) return WmsRefreshOutcome.Failed(WmsAddProblem.NO_WEB_MERCATOR)
                val missing = library.refresh(serviceId, read.capabilities) ?: return WmsRefreshOutcome.NotFound
                WmsRefreshOutcome.Refreshed(missing)
            }
        }
    }

    private suspend fun addAddress(address: WmsAddress, mode: WmsRequestMode, presetKey: String?, name: String?): WmsAddOutcome {
        library.findByAddress(address)?.let { return WmsAddOutcome.AlreadyThere(it) }
        val capabilities = when (val read = read(address, first = VERSION_1_3_0)) {
            is Read.Failed -> return WmsAddOutcome.Failed(read.problem, read.detail)
            is Read.Ok -> read.capabilities
        }
        if (!capabilities.hasAddableLayer) return WmsAddOutcome.Failed(WmsAddProblem.NO_WEB_MERCATOR)
        return when (val saved = library.add(address, capabilities, mode, presetKey, name)) {
            is WmsAddResult.Added -> WmsAddOutcome.Added(saved.service)
            is WmsAddResult.AlreadyThere -> WmsAddOutcome.AlreadyThere(saved.service)
        }
    }

    private sealed interface Read {
        data class Ok(val capabilities: WmsCapabilities) : Read

        data class Failed(val problem: WmsAddProblem, val detail: String? = null) : Read
    }

    /** Pide en la versión [first] y, si no la ofrece, en la otra; si fallan las dos, sale el motivo de la última. */
    private suspend fun read(address: WmsAddress, first: String): Read {
        val versions = listOf(first, if (first == VERSION_1_3_0) VERSION_1_1_1 else VERSION_1_3_0)
        var last: Read.Failed? = null
        for (version in versions) {
            when (val result = request(address, version)) {
                is Read.Ok -> return result
                is Read.Failed -> {
                    last = result
                    if (result.problem !in OTHER_VERSION_MAY_WORK) return result
                }
            }
        }
        return last ?: Read.Failed(WmsAddProblem.NO_RESPONSE)
    }

    private suspend fun request(address: WmsAddress, version: String): Read = try {
        http.get(WmsRequests.capabilities(address, version)) { response ->
            try {
                if (response.code == HTTP_OK) {
                    Read.Ok(WmsCapabilitiesReader.read(response.body, maxBytes))
                } else {
                    // Algunos servicios contestan con un error HTTP y una excepción de servicio con el motivo.
                    errorBody(response)
                }
            } catch (error: WmsCapabilitiesException) {
                failed(error)
            }
        }
    } catch (_: WmsTimeoutException) {
        Read.Failed(WmsAddProblem.TIMEOUT)
    } catch (_: IOException) {
        Read.Failed(WmsAddProblem.NO_RESPONSE)
    }

    private fun errorBody(response: WmsHttpResponse): Read = try {
        WmsCapabilitiesReader.read(response.body, maxBytes)
        Read.Failed(WmsAddProblem.HTTP_ERROR, response.code.toString())
    } catch (error: WmsCapabilitiesException) {
        when (error.problem) {
            WmsCapabilitiesProblem.SERVICE_EXCEPTION -> failed(error)
            else -> Read.Failed(WmsAddProblem.HTTP_ERROR, response.code.toString())
        }
    }

    private fun failed(error: WmsCapabilitiesException): Read.Failed = when (error.problem) {
        WmsCapabilitiesProblem.NOT_CAPABILITIES -> Read.Failed(WmsAddProblem.NOT_CAPABILITIES)
        WmsCapabilitiesProblem.SERVICE_EXCEPTION -> Read.Failed(WmsAddProblem.SERVICE_EXCEPTION, error.serviceMessage)
        WmsCapabilitiesProblem.MALFORMED -> Read.Failed(WmsAddProblem.MALFORMED)
        WmsCapabilitiesProblem.TOO_LARGE -> Read.Failed(WmsAddProblem.TOO_LARGE)
    }

    private companion object {
        const val VERSION_1_3_0 = "1.3.0"
        const val VERSION_1_1_1 = "1.1.1"
        const val HTTP_OK = 200
        val OTHER_VERSION_MAY_WORK = setOf(WmsAddProblem.HTTP_ERROR, WmsAddProblem.NOT_CAPABILITIES, WmsAddProblem.SERVICE_EXCEPTION)
    }
}

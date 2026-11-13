package es.topophone.data.wms

import es.topophone.core.model.wms.WmsLayerInfo
import es.topophone.core.model.wms.WmsRequestMode
import es.topophone.core.model.wms.WmsService

/** Los formatos van en una columna como lista separada por `|`. */
internal const val WMS_LIST_SEPARATOR = "|"

internal fun WmsServiceEntity.toModel() = WmsService(
    id = id,
    name = name,
    address = address,
    title = title,
    version = version,
    attribution = attribution,
    presetKey = presetKey,
    requestMode = WmsRequestMode.valueOf(requestMode),
    mapFormats = splitList(mapFormats),
    infoFormats = splitList(infoFormats),
    createdAtMs = createdAt,
    updatedAtMs = updatedAt,
)

internal fun WmsLayerEntity.toModel() = WmsLayerInfo(ordinal, depth, name, title, summary, transparent, queryable, mercatorCode)

private fun splitList(text: String) = if (text.isEmpty()) emptyList() else text.split(WMS_LIST_SEPARATOR)

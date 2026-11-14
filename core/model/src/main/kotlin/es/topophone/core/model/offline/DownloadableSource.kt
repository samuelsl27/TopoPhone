package es.topophone.core.model.offline

/** Qué fuentes se pueden guardar en una zona (S037, glosario «Fuente descargable»); el Catastro y los WMS en «una imagen» nunca. */
enum class SourceKind(val key: String) {
    IGN_VECTOR("ign"),
    PNOA("pnoa"),
    OPENFREEMAP("ofm"),
    WMS("wms"),
    ;

    companion object {
        fun fromKey(key: String): SourceKind? = entries.firstOrNull { it.key == key }
    }
}

/** El tipo de una fuente de teselas: decide el nivel de tesela que MapLibre pide a cada zoom (plan, A2). */
enum class TileKind { RASTER, VECTOR }

/** Una fuente de teselas de un estilo: su tipo, el tamaño de tesela en píxeles y los niveles de tesela que sirve (`minzoom` y `maxzoom`). */
data class TileSourceSpec(val kind: TileKind, val tileSize: Int, val minZoom: Int, val maxZoom: Int)

/**
 * Una fuente que se puede guardar en una zona sin conexión (S037/RF-8): un mapa base o una capa WMS por teselas. [title] es el texto que ve el usuario.
 * Una capa WMS lleva su [serviceId] y su [layerName]; las demás, ninguno de los dos.
 */
data class DownloadableSource(val kind: SourceKind, val title: String, val serviceId: String? = null, val layerName: String? = null) {
    init {
        if (kind == SourceKind.WMS) {
            require(!serviceId.isNullOrBlank()) { "Una capa WMS lleva el servicio" }
            require(!layerName.isNullOrBlank()) { "Una capa WMS lleva el nombre de la capa" }
        } else {
            require(serviceId == null && layerName == null) { "Solo las capas WMS llevan servicio y capa" }
        }
    }

    /** La clave estable de la fuente: `ign`, `pnoa`, `ofm` o `wms:<servicio>:<capa>`. */
    val key: String get() = if (kind == SourceKind.WMS) "wms:$serviceId:$layerName" else kind.key

    /** Las fuentes de teselas que MapLibre descarga con esta fuente, medidas en T1 (plan, «Resultados del spike»). El IGN suma su relieve. */
    val tileSources: List<TileSourceSpec>
        get() = when (kind) {
            SourceKind.IGN_VECTOR -> listOf(TileSourceSpec(TileKind.VECTOR, 512, 0, IGN_VECTOR_MAX_ZOOM), IGN_RELIEF)
            SourceKind.PNOA -> listOf(TileSourceSpec(TileKind.RASTER, 256, 0, PNOA_MAX_ZOOM))
            SourceKind.OPENFREEMAP -> listOf(TileSourceSpec(TileKind.VECTOR, 512, 0, OPENFREEMAP_MAX_ZOOM))
            SourceKind.WMS -> listOf(TileSourceSpec(TileKind.RASTER, 256, 0, WMS_MAX_ZOOM))
        }

    /** S037/RF-9: solo OpenFreeMap tiene un tope de descarga. */
    val hasDownloadCap: Boolean get() = kind == SourceKind.OPENFREEMAP

    private companion object {
        /** Los mismos niveles que S009 deja al estilo del IGN (`IgnStylePatch`) y a la ortofoto (`MapStyles`). */
        const val IGN_VECTOR_MAX_ZOOM = 17
        const val PNOA_MAX_ZOOM = 19
        const val OPENFREEMAP_MAX_ZOOM = 14
        const val WMS_MAX_ZOOM = 22
        val IGN_RELIEF = TileSourceSpec(TileKind.RASTER, 512, 5, 15)
    }
}

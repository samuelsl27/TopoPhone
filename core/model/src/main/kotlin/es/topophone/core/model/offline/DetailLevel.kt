package es.topophone.core.model.offline

/**
 * El nivel de detalle de una zona sin conexión (S037/RF-8, glosario «Nivel de detalle»): «Normal» hasta 1:5.000 aprox., «Alto» hasta 1:1.000 y «Máximo»
 * hasta el zoom máximo del mapa (1 m en la barra de escala, S009/RF-12). El zoom del mapa de cada nivel se calcula en `core:geodesy` con la latitud
 * del centro de la zona; [scaleDenominator] es null en «Máximo».
 */
enum class DetailLevel(val key: String, val scaleDenominator: Double?) {
    NORMAL("normal", 5_000.0),
    HIGH("high", 1_000.0),
    MAX("max", null),
    ;

    companion object {
        fun fromKey(key: String): DetailLevel? = entries.firstOrNull { it.key == key }
    }
}

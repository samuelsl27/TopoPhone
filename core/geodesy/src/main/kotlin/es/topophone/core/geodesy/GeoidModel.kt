package es.topophone.core.geodesy

/** Modelo de geoide sobre una rejilla de ondulaciones N (metros), H = h − N (S002/RF-38). */
class GeoidModel(val name: String, val attribution: String, val grid: GeoTiffGrid) {

    /** Ondulación en el punto, o `null` fuera de la cobertura (S002/RF-39). */
    fun undulationM(latitudeDeg: Double, longitudeDeg: Double): Double? = grid.interpolate(latitudeDeg, longitudeDeg)
}

/** Modelos de geoide incluidos en la app (rejillas del CDN de PROJ, `tools/geodesia/rejillas.py`). */
object GeoidModels {

    /** EGM08-REDNAP de la Península y Baleares (IGN). Se descodifica la primera vez que se usa. */
    val egm08RednapPeninsula: GeoidModel by lazy {
        GeoidModel(
            name = "EGM08-REDNAP",
            attribution = "EGM08-REDNAP © Instituto Geográfico Nacional (IGN), CC-BY 4.0",
            grid = GeoTiffGrid.read(resource("/geoid/es_ign_egm08-rednap.tif")),
        )
    }

    private fun resource(path: String): ByteArray = GeoidModels::class.java.getResourceAsStream(path)?.use { it.readBytes() }
        ?: error("Falta el recurso $path")
}

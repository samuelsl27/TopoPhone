package es.topophone.core.model.project

/**
 * El plano de terreno de un proyecto (S058/RF-3, RF-6): las coordenadas de terreno son las de cuadrícula escaladas con el [factor] respecto a un origen,
 * que se guarda por su posición del suelo ([originLatitudeDeg], [originLongitudeDeg], en el datum de los cálculos y sin redondear) y no por sus
 * coordenadas de cuadrícula: así sigue en el mismo punto al cambiar el SC del proyecto (S035).
 */
data class TerrainPlane(val originLatitudeDeg: Double, val originLongitudeDeg: Double, val factor: Double) {
    /** `latitud;longitud;factor`, con los números sin redondear y con punto decimal. */
    fun toText(): String = "$originLatitudeDeg;$originLongitudeDeg;$factor"

    companion object {
        /** `null` si el texto no es un plano. */
        fun parseOrNull(text: String?): TerrainPlane? {
            if (text.isNullOrEmpty()) return null
            val v = text.split(';').map { it.toDoubleOrNull() ?: return null }
            return if (v.size == 3) TerrainPlane(v[0], v[1], v[2]) else null
        }
    }
}

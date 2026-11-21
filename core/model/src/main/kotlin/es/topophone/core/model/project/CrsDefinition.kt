package es.topophone.core.model.project

/** Recuadro en geográficas, en grados; si [westDeg] es mayor que [eastDeg] cruza el antimeridiano (S034/RF-13). */
data class GeoBox(val southDeg: Double, val westDeg: Double, val northDeg: Double, val eastDeg: Double) {
    fun contains(latitudeDeg: Double, longitudeDeg: Double): Boolean {
        if (latitudeDeg !in southDeg..northDeg) return false
        return if (westDeg <= eastDeg) longitudeDeg in westDeg..eastDeg else longitudeDeg >= westDeg || longitudeDeg <= eastDeg
    }

    fun toText(): String = "$southDeg,$westDeg,$northDeg,$eastDeg"

    companion object {
        fun parse(text: String): GeoBox? {
            if (text.isEmpty()) return null
            val v = text.split(',').map { it.toDouble() }
            require(v.size == 4) { "Recuadro mal formado: $text" }
            return GeoBox(v[0], v[1], v[2], v[3])
        }
    }
}

/**
 * La definición completa de un SC de la biblioteca (S034/RF-13): lo que el proyecto guarda para que el SC y su transformación no cambien aunque
 * se actualice la biblioteca. Son solo datos (cadenas y números): `core:geodesy` los convierte en proyección y transformación. Ocupa unos
 * cientos de bytes en su [toText].
 *
 * @property method nombre corto del método de proyección (`tm`, `lcc2`, `geo`…); [parameters] con los nombres de S032, los ángulos en grados y los
 * falsos en la unidad lineal ([unitMetres] metros por unidad).
 * @property ellipsoidInverseFlattening 0 para una esfera.
 * @property frame datum de las correcciones al que lleva [transformationSteps]: `ETRS89` o `WGS84`.
 * @property transformationSteps los pasos de la transformación en la notación del catálogo (`null`, `H;…`, `G;…`, separados por `|`).
 */
data class CrsDefinition(
    val epsg: Int,
    val name: String,
    val projected: Boolean,
    val datumCode: Int,
    val datumName: String,
    val ellipsoidSemiMajorM: Double,
    val ellipsoidInverseFlattening: Double,
    val primeMeridianDeg: Double,
    val method: String,
    val parameters: Map<String, Double>,
    val unitMetres: Double,
    val area: GeoBox?,
    val frame: String,
    val transformationName: String,
    val transformationAccuracyM: Double?,
    val transformationArea: GeoBox?,
    val transformationSteps: String,
) {
    /** Los nombres de las rejillas de la transformación. */
    val gridNames: List<String>
        get() = transformationSteps.split('|').filter { it.startsWith("G;") }.map { step ->
            step.split(';').first { it.startsWith("grid=") }.removePrefix("grid=")
        }

    /** Una línea de texto, versionada (`v1`), sin saltos de línea ni tabuladores en los nombres. */
    fun toText(): String = listOf(
        VERSION,
        epsg.toString(),
        clean(name),
        if (projected) "P" else "G",
        datumCode.toString(),
        clean(datumName),
        "$ellipsoidSemiMajorM,$ellipsoidInverseFlattening",
        primeMeridianDeg.toString(),
        method,
        parameters.entries.joinToString(";") { "${it.key}=${it.value}" },
        unitMetres.toString(),
        area?.toText().orEmpty(),
        frame,
        clean(transformationName),
        transformationAccuracyM?.toString().orEmpty(),
        transformationArea?.toText().orEmpty(),
        transformationSteps,
    ).joinToString("\t")

    companion object {
        private const val VERSION = "v1"
        private const val FIELDS = 17

        private fun clean(text: String) = text.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

        /** `null` si el texto no es una definición válida. */
        fun parseOrNull(text: String): CrsDefinition? = try {
            parse(text)
        } catch (_: IllegalArgumentException) {
            null
        }

        /** @throws IllegalArgumentException si el texto no es una definición de versión conocida o está mal formado. */
        fun parse(text: String): CrsDefinition {
            val f = text.split('\t')
            require(f.size == FIELDS && f[0] == VERSION) { "Definición de SC desconocida" }
            return try {
                val ellipsoid = f[6].split(',').map { it.toDouble() }
                CrsDefinition(
                    epsg = f[1].toInt(),
                    name = f[2],
                    projected = f[3] == "P",
                    datumCode = f[4].toInt(),
                    datumName = f[5],
                    ellipsoidSemiMajorM = ellipsoid[0],
                    ellipsoidInverseFlattening = ellipsoid[1],
                    primeMeridianDeg = f[7].toDouble(),
                    method = f[8],
                    parameters = if (f[9].isEmpty()) {
                        emptyMap()
                    } else {
                        f[9].split(';').associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }
                    },
                    unitMetres = f[10].toDouble(),
                    area = GeoBox.parse(f[11]),
                    frame = f[12],
                    transformationName = f[13],
                    transformationAccuracyM = f[14].toDoubleOrNull(),
                    transformationArea = GeoBox.parse(f[15]),
                    transformationSteps = f[16],
                )
            } catch (e: NumberFormatException) {
                throw IllegalArgumentException("Definición de SC mal formada", e)
            } catch (e: IndexOutOfBoundsException) {
                throw IllegalArgumentException("Definición de SC mal formada", e)
            }
        }
    }
}

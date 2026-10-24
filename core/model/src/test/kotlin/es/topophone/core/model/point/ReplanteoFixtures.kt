package es.topophone.core.model.point

import java.io.File

/**
 * Los CSV de `fixtures/replanteo/` calculados con pyproj (constitución nº 2) como filas con el nombre de cada columna, y los
 * puntos replanteados que salen de las filas de `replanteo-informe.csv` (S019).
 */
object ReplanteoFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    fun rows(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/replanteo/$name").readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first().split(",")
        return lines.drop(1).map { line -> header.zip(line.split(",")).toMap() }
    }

    fun informe(): List<Map<String, String>> = rows("replanteo-informe.csv")

    fun resumen(): List<Map<String, String>> = rows("replanteo-resumen.csv")

    private fun Map<String, String>.number(key: String) = getValue(key).toDouble()

    private fun Map<String, String>.optional(key: String) = getValue(key).takeIf { it.isNotEmpty() }?.toDouble()

    /** El punto replanteado de una fila: sus coordenadas medidas, su diseño por valor y lo guardado de S019. */
    fun point(row: Map<String, String>, index: Int): Point {
        val measured = PointCoordinates(
            GeodeticPosition(row.number("medido_lat"), row.number("medido_lon"), row.number("medido_gh")),
            row.optional("medido_e"),
            row.optional("medido_n"),
            row.number("medido_h"),
        )
        val design = PointCoordinates(
            GeodeticPosition(row.number("diseno_lat"), row.number("diseno_lon"), row.number("diseno_gh")),
            row.optional("diseno_e"),
            row.optional("diseno_n"),
            row.optional("diseno_h"),
        )
        val within = row.getValue("dentro").takeIf { it.isNotEmpty() }?.let { it == "1" }
        val ref = StakeoutRef(
            designId = "design-${row.getValue("proyecto")}-${row.getValue("diseno")}",
            designName = row.getValue("diseno"),
            design = design,
            dEastM = row.number("d_este"),
            dNorthM = row.number("d_norte"),
            dHorizontalM = row.number("d_horizontal"),
            dHeightM = row.optional("d_cota"),
            designElevationM = row.optional("cota_usada"),
            toleranceHM = row.optional("tol_h"),
            toleranceVM = row.optional("tol_v"),
            withinTolerance = within,
        )
        val measurement = Measurement(
            antenna = measured.ground,
            poleHeightM = 1.8,
            quality = PointQuality("FIX", 24, 0.6, 1.0, 0.01, 0.015, MeasurementMode.INSTANT, 1, 0, fromReplay = false),
            rawNmea = "",
            outOfTolerance = emptySet(),
            measuredFromMs = row.getValue("creado_ms").toLong(),
            measuredToMs = row.getValue("creado_ms").toLong() + 200,
            coordinates = measured,
        )
        return Point.measured(
            id = "p-$index",
            projectId = row.getValue("proyecto"),
            name = row.getValue("nombre"),
            description = row.getValue("descripcion"),
            measurement = measurement,
            createdAtMs = row.getValue("creado_ms").toLong(),
            staked = ref,
        )
    }
}

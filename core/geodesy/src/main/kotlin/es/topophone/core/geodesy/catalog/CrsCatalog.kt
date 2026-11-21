package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.Ellipsoid
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * La biblioteca de SC (S034/RF-4): los SC de EPSG que la app sabe calcular, con sus datums, las transformaciones hasta ETRS89 y WGS 84 y las
 * rejillas del CDN de PROJ. La genera `tools/geodesia/catalogo.py` (ADR-3) y se lee de un texto TSV comprimido; ver ahí el formato.
 */
class CrsCatalog private constructor(
    val crs: List<CrsEntry>,
    val datums: Map<Int, DatumEntry>,
    val options: List<TransformationOption>,
    val grids: Map<String, GridEntry>,
    val countries: List<Country>,
) {
    private val byEpsg: Map<Int, CrsEntry> = crs.associateBy { it.epsg }
    private val optionsByDatum: Map<Pair<Int, CorrectionFrame>, List<TransformationOption>> = options.groupBy { it.datumCode to it.target }

    fun crsByEpsg(epsg: Int): CrsEntry? = byEpsg[epsg]

    /** Las transformaciones del datum hasta [target], de más a menos precisas (las de precisión desconocida, al final). */
    fun optionsFor(datumCode: Int, target: CorrectionFrame): List<TransformationOption> = optionsByDatum[datumCode to target].orEmpty()

    companion object {
        private const val RESOURCE = "/catalog/crs.tsv.gz"

        /** El catálogo incluido en la app; se lee la primera vez que se usa. */
        val bundled: CrsCatalog by lazy {
            val stream = CrsCatalog::class.java.getResourceAsStream(RESOURCE) ?: error("Falta el recurso $RESOURCE")
            stream.use { load(it) }
        }

        /** Lee un catálogo comprimido con gzip. */
        fun load(stream: InputStream): CrsCatalog = parse(GZIPInputStream(stream).bufferedReader(Charsets.UTF_8).readText())

        fun parse(text: String): CrsCatalog {
            val sections = HashMap<String, MutableList<List<String>>>()
            var current: MutableList<List<String>>? = null
            for (line in text.lineSequence()) {
                when {
                    line.isEmpty() || line.startsWith("# ") -> Unit
                    line.startsWith("#") -> current = sections.getOrPut(line.substring(1)) { mutableListOf() }
                    else -> (current ?: error("Fila fuera de una sección: $line")).add(line.split('\t'))
                }
            }
            fun section(name: String) = sections[name] ?: error("Falta la sección #$name")

            val ellipsoids = section("ellipsoids").associate { it[0] to ellipsoid(it[1].toDouble(), it[2].toDouble()) }
            val datums = section("datums").map { f ->
                DatumEntry(
                    f[0].toInt(),
                    f[1],
                    ellipsoids[f[2]] ?: error("Elipsoide desconocido: ${f[2]}"),
                    f[3].toDouble(),
                    CatalogArea.parse(f[4]),
                )
            }.associateBy { it.code }
            val crs = section("crs").map { f ->
                CrsEntry(
                    epsg = f[0].toInt(),
                    name = f[1],
                    kind = if (f[2] == "G") CrsKind.GEOGRAPHIC else CrsKind.PROJECTED,
                    datumCode = f[3].toInt(),
                    method = f[4],
                    parameters = parameters(f[5]),
                    unitMetres = f[6].toDouble(),
                    area = CatalogArea.parse(f[7]),
                    countries = f[8].split(',').filter { it.isNotEmpty() },
                    areaName = f[9],
                )
            }
            val options = section("options").map { f ->
                TransformationOption(
                    datumCode = f[0].toInt(),
                    target = if (f[1] == "E") CorrectionFrame.ETRS89 else CorrectionFrame.WGS84,
                    name = f[2],
                    accuracyM = f[3].toDoubleOrNull(),
                    area = CatalogArea.parse(f[4]),
                    steps = parseSteps(f[5]),
                    stepsText = f[5],
                )
            }
            val licenses = section("licenses").associate { f -> f[0] to GridLicense(f[0], f[1], f[2] == "1", f[3]) }
            val grids = section("grids").map { f ->
                GridEntry(
                    name = f[0],
                    kind = if (f[1] == "V") GridKind.GEOID else GridKind.HORIZONTAL,
                    sizeBytes = f[2].toLong(),
                    sha256 = f[3],
                    license = licenses[f[4]] ?: error("Licencia desconocida: ${f[4]}"),
                    source = f[5],
                    sourceUrl = f[6],
                    verticalCrs = f[7],
                    area = CatalogArea.parse(f[8]),
                    bundled = f[9] == "1",
                )
            }.associateBy { it.name }
            val countries = section("countries").map { Country(it[0], it[1], it[2]) }
            return CrsCatalog(crs, datums, options, grids, countries)
        }

        /** El elipsoide con el inverso del aplanamiento 0 del catálogo como esfera (infinito, como en los vectores de S032). */
        private fun ellipsoid(semiMajorM: Double, inverseFlattening: Double) =
            Ellipsoid(semiMajorM, if (inverseFlattening == 0.0) Double.POSITIVE_INFINITY else inverseFlattening)

        private fun parameters(text: String): Map<String, Double> =
            if (text.isEmpty()) emptyMap() else text.split(';').associate { it.substringBefore('=') to it.substringAfter('=').toDouble() }

        private fun triple(text: String): Triple<Double, Double, Double> = text.split(',').map {
            it.toDouble()
        }.let { Triple(it[0], it[1], it[2]) }

        private fun ellipsoidOf(text: String): Ellipsoid = text.split(',').let { ellipsoid(it[0].toDouble(), it[1].toDouble()) }

        /** Los pasos de una transformación en la notación del catálogo, separados por `|`. */
        fun parseSteps(text: String): List<StepSpec> = text.split('|').map(::step)

        private fun step(text: String): StepSpec {
            if (text == "null") return StepSpec.Null
            val fields = text.split(';')
            val values = fields.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
            return when (fields[0]) {
                "H" -> {
                    val p = values.getValue("p").split(',').map { it.toDouble() }
                    StepSpec.Helmert(
                        before = ellipsoidOf(values.getValue("A")),
                        after = ellipsoidOf(values.getValue("B")),
                        inverse = values.getValue("inv") == "1",
                        convention = values.getValue("conv"),
                        translationM = Triple(p[0], p[1], p[2]),
                        rotationArcSec = Triple(p[3], p[4], p[5]),
                        scalePpm = p[6],
                        pivotM = values["px"]?.let(::triple),
                    )
                }
                "G" -> StepSpec.Grid(values.getValue("grid"), values.getValue("inv") == "1")
                else -> error("Paso desconocido: $text")
            }
        }
    }
}

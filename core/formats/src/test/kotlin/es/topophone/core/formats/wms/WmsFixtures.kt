package es.topophone.core.formats.wms

import java.io.File

/** Los documentos de `fixtures/formatos/wms/` y lo que debe leer el lector de cada uno, que calcula `tools/formatos/generar_wms.py` (S036). */
object WmsFixtures {
    private val root: File by lazy {
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    }

    val folder: File get() = File(root, "fixtures/formatos/wms")

    fun bytes(name: String): ByteArray = File(folder, name).readBytes()

    class ExpectedLayer(
        val ordinal: Int,
        val depth: Int,
        val name: String?,
        val title: String,
        val abstract: String,
        val transparent: Boolean,
        val queryable: Boolean,
        val code: String?,
    )

    /** Lo esperado de un documento: su estado (`ok` o el motivo de que no sea de capacidades) y, si es `ok`, el servicio y sus capas. */
    class Expected(
        val file: String,
        val state: String,
        val version: String,
        val title: String,
        val attribution: String,
        val mapFormats: List<String>,
        val infoFormats: List<String>,
        val message: String,
        val layers: List<ExpectedLayer>,
    )

    /** Un texto de `esperado-wms.txt`: `\\p` es `|`, `\\n` un salto de línea y `\\\\` una barra. */
    private fun field(text: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(
                    when (text[i + 1]) {
                        'p' -> '|'
                        'n' -> '\n'
                        else -> text[i + 1]
                    },
                )
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun list(text: String) = if (text.isEmpty()) emptyList() else text.split(';')

    fun expected(): List<Expected> {
        val layers = LinkedHashMap<String, MutableList<ExpectedLayer>>()
        val services = ArrayList<List<String>>()
        for (line in File(folder, "esperado-wms.txt").readLines(Charsets.UTF_8).filter { it.isNotBlank() }) {
            val p = line.split('|')
            if (p[0] == "service") {
                services += p
                layers[p[1]] = ArrayList()
            } else {
                layers.getValue(p[1]) += ExpectedLayer(
                    ordinal = p[2].toInt(),
                    depth = p[3].toInt(),
                    name = field(p[4]).ifEmpty { null },
                    title = field(p[5]),
                    abstract = field(p[6]),
                    transparent = p[7] == "1",
                    queryable = p[8] == "1",
                    code = p[9].ifEmpty { null },
                )
            }
        }
        return services.map { p ->
            Expected(
                file = p[1],
                state = p[2],
                version = p[3],
                title = field(p[4]),
                attribution = field(p[5]),
                mapFormats = list(p[6]),
                infoFormats = list(p[7]),
                message = field(p[8]),
                layers = layers.getValue(p[1]),
            )
        }
    }

    /** Las cajas `caja|sur|oeste|norte|este|x0|y0|x1|y1` de `esperado-cajas.txt`: grados y EPSG:3857 de pyproj. */
    class Box(
        val south: Double,
        val west: Double,
        val north: Double,
        val east: Double,
        val x0: Double,
        val y0: Double,
        val x1: Double,
        val y1: Double,
    )

    fun boxes(): List<Box> = File(folder, "esperado-cajas.txt").readLines().filter { it.isNotBlank() }.map { line ->
        val v = line.split('|').drop(1).map { it.toDouble() }
        Box(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7])
    }
}

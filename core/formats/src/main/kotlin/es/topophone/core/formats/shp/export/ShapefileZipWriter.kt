package es.topophone.core.formats.shp.export

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.Normalizer
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Escribe el ZIP de la exportación a Shapefile (S055/RF-3, RF-4, RF-10, RF-11, plan A5): un Shapefile por capa con su `.shp`, `.shx`,
 * `.dbf`, `.prj` y `.cpg`, que se llama como el proyecto limpiado y lleva el sufijo de su capa (`_puntos`, `_lineas`, `_poligonos`). No
 * escribe capas vacías: [ShpExport] no las trae. Un fallo lanza la excepción de la escritura; quien llama escribe en un fichero temporal
 * y solo lo da por bueno si termina (RNF-T6).
 */
object ShapefileZipWriter {
    private const val FALLBACK_NAME = "proyecto"

    /**
     * El nombre de los Shapefiles a partir del nombre del proyecto (RF-4): letras sin acentos, cifras, `_` y `-`; todo lo demás pasa a `_`
     * y, si no queda nada tras quitar los espacios de los extremos, `proyecto`.
     */
    fun baseName(projectName: String): String {
        val decomposed = Normalizer.normalize(projectName.trim(), Normalizer.Form.NFD)
        val plain = decomposed.filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        val clean = plain.map { if (it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '-') it else '_' }
            .joinToString("")
        return clean.ifBlank { FALLBACK_NAME }
    }

    /** Los nombres de los ficheros de [layer] dentro del ZIP: `.shp`, `.shx`, `.dbf`, `.prj` (si lo hay) y `.cpg`. */
    fun entryNames(projectName: String, layer: ShpLayer): List<String> {
        val stem = baseName(projectName) + layer.kind.suffix
        return listOfNotNull("$stem.shp", "$stem.shx", "$stem.dbf", "$stem.prj".takeIf { layer.prj != null }, "$stem.cpg")
    }

    /**
     * Escribe [export] en [out] como un ZIP (Deflate) y lo termina sin cerrar [out]. [date] es la de la cabecera de los `.dbf`.
     * Devuelve el resumen de [export] con los textos que se han recortado a 254 bytes (RF-10, RF-22).
     */
    fun write(export: ShpExport, projectName: String, out: OutputStream, date: LocalDate = LocalDate.now()): ShpExportSummary {
        require(!export.isEmpty) { "No hay ninguna capa que escribir" }
        val zip = ZipOutputStream(out)
        var truncated = 0
        for (layer in export.layers) {
            val stem = baseName(projectName) + layer.kind.suffix
            val shx = ByteArrayOutputStream()
            zip.putNextEntry(ZipEntry("$stem.shp"))
            ShpWriter.write(layer.kind.type, layer.geometries, zip, shx)
            zip.closeEntry()
            entry(zip, "$stem.shx", shx.toByteArray())
            zip.putNextEntry(ZipEntry("$stem.dbf"))
            truncated += DbfWriter.write(layer.columns, layer.rows, zip, date).truncatedTexts
            zip.closeEntry()
            layer.prj?.let { entry(zip, "$stem.prj", it.toByteArray(Charsets.US_ASCII)) }
            entry(zip, "$stem.cpg", DbfWriter.CPG)
        }
        zip.finish()
        zip.flush()
        return export.summary.copy(truncatedTexts = truncated)
    }

    private fun entry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }
}

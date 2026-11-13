package es.topophone.core.formats.photo

import es.topophone.core.formats.csv.CsvExport
import es.topophone.core.model.point.PhotoOrigin

/**
 * Una fila del índice del ZIP de fotos (S056/RF-6): el nombre de la foto en el ZIP, el nombre real del punto, el número de la foto en su
 * punto, cuándo se hizo (UTC, ms), de dónde viene y los bytes del fichero que se escribió.
 */
class PhotoIndexRow(
    val photoName: String,
    val pointName: String,
    val number: Int,
    val takenAtMs: Long,
    val origin: PhotoOrigin,
    val sizeBytes: Long,
)

/**
 * El índice `fotos.csv` de un ZIP de fotos (S056/RF-6, RF-7): una cabecera fija en inglés, como las de S012, y una fila por foto en el orden
 * del ZIP. Coma como separador, `\r\n` y los textos con coma, comillas o saltos de línea entre comillas dobles (RFC 4180, como el CSV de
 * puntos); la fecha en ISO 8601 UTC con la Z al segundo y los números sin separadores (RNF-T9). Quien lo escribe en un flujo lo codifica en
 * UTF-8 sin BOM.
 */
object PhotoIndexCsv {
    const val FILE_NAME = "fotos.csv"
    val HEADER = listOf("Photo", "Point", "Number", "Date", "Source", "Size")
    private const val SEPARATOR = ','

    fun write(rows: Iterable<PhotoIndexRow>, out: Appendable) {
        out.append(HEADER.joinToString(SEPARATOR.toString())).append(CsvExport.LINE_END)
        for (row in rows) {
            out.append(CsvExport.quoted(row.photoName, SEPARATOR)).append(SEPARATOR)
                .append(CsvExport.quoted(row.pointName, SEPARATOR)).append(SEPARATOR)
                .append(row.number.toString()).append(SEPARATOR)
                .append(CsvExport.utc(row.takenAtMs)).append(SEPARATOR)
                .append(if (row.origin == PhotoOrigin.CAMERA) "camera" else "gallery").append(SEPARATOR)
                .append(row.sizeBytes.toString())
                .append(CsvExport.LINE_END)
        }
    }
}

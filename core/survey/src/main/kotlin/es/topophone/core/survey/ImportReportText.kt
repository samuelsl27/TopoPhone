package es.topophone.core.survey

import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportRowError

/**
 * El informe de las filas con error de una importación como texto, para guardarlo o compartirlo (S028/RF-24). Los textos que ve el
 * usuario los da quien llama (`strings.xml`, RNF-T7): este módulo no tiene recursos.
 */
object ImportReportText {
    /**
     * El título, el [summary], una línea en blanco y una línea por fila errónea, en el orden dado, con [lineFormat] (la línea y el
     * motivo ya en texto); sin errores, [emptyText]. Acaba en salto de línea.
     */
    fun build(
        title: String,
        summary: String,
        errors: List<ImportRowError>,
        reasonText: (ImportErrorReason) -> String,
        lineFormat: (line: Int, reason: String) -> String,
        emptyText: String,
    ): String = buildString {
        append(title).append('\n')
        append(summary).append('\n')
        append('\n')
        if (errors.isEmpty()) {
            append(emptyText).append('\n')
        } else {
            errors.forEach { append(lineFormat(it.line, reasonText(it.reason))).append('\n') }
        }
    }
}

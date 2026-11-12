package es.topophone.app.ui.gpkg

import es.topophone.core.model.cad.GpkgIgnoredReason
import es.topophone.core.model.cad.GpkgImportSummary

/** Los textos del resumen de la importación, que son de la interfaz (`strings.xml`) y no del módulo de dominio (S048/RF-25). */
class GpkgSummaryLabels(
    val title: String,
    val layer: String,
    val imported: String,
    val outsideArea: String,
    val ignored: String,
    val omittedAttributes: String,
    val total: String,
    val leftOut: String,
    val wgs84: String,
    val reason: (GpkgIgnoredReason) -> String,
    /** El aviso de los objetos de un Shapefile sin registro en su `.dbf` (S030/RF-20). */
    val withoutAttributes: String = "",
)

/** El resumen de una importación de GeoPackage como un fichero de texto, una línea por concepto y capa (S048/RF-25). */
object GpkgSummaryText {
    fun build(summary: GpkgImportSummary, labels: GpkgSummaryLabels): String = buildString {
        appendLine(labels.title)
        for (layer in summary.layers) {
            appendLine("${labels.layer}: ${layer.table}")
            appendLine("  ${labels.imported}: ${layer.imported}")
            appendLine("  ${labels.outsideArea}: ${layer.outsideArea}")
            appendLine("  ${labels.ignored}: ${layer.ignoredTotal}")
            layer.ignored.entries.sortedBy { it.key.ordinal }.forEach { appendLine("    ${labels.reason(it.key)}: ${it.value}") }
            if (layer.omittedAttributes > 0) appendLine("  ${labels.omittedAttributes}: ${layer.omittedAttributes}")
            if (layer.withoutAttributes > 0) appendLine("  ${labels.withoutAttributes}: ${layer.withoutAttributes}")
        }
        appendLine("${labels.total}: ${summary.importedTotal}")
        if (summary.leftOutTables.isNotEmpty()) appendLine("${labels.leftOut}: ${summary.leftOutTables.joinToString(", ")}")
        if (summary.wgs84Layers.isNotEmpty()) appendLine("${labels.wgs84}: ${summary.wgs84Layers.joinToString(", ")}")
    }
}

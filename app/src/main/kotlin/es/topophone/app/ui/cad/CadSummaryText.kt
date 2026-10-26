package es.topophone.app.ui.cad

import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.cad.CadImportSummary

/** Los textos del resumen de la importación, que son de la interfaz (`strings.xml`) y no del módulo de dominio (S040/RF-18). */
class CadSummaryLabels(
    val title: String,
    val imported: String,
    val ignored: String,
    val layers: String,
    val incomplete: String,
    val kind: (CadEntityKind) -> String,
    val ignoredItem: (CadIgnored) -> String,
)

/** El resumen de una importación como un fichero de texto, una línea por concepto (S040/RF-18). */
object CadSummaryText {
    fun build(summary: CadImportSummary, labels: CadSummaryLabels): String = buildString {
        appendLine(labels.title)
        appendLine("${labels.imported}: ${summary.importedTotal}")
        summary.imported.entries.sortedBy { it.key.ordinal }.forEach { appendLine("  ${labels.kind(it.key)}: ${it.value}") }
        appendLine("${labels.ignored}: ${summary.ignoredTotal}")
        summary.ignored.entries.sortedBy {
            labels.ignoredItem(it.key)
        }.forEach { appendLine("  ${labels.ignoredItem(it.key)}: ${it.value}") }
        appendLine("${labels.layers}: ${summary.layers}")
        if (summary.incomplete) appendLine(labels.incomplete)
    }
}

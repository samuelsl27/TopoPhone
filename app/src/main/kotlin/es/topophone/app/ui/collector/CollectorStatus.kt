package es.topophone.app.ui.collector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.field.SettingPillData
import es.topophone.app.ui.field.SettingsRow
import es.topophone.app.ui.projects.unitSymbol
import es.topophone.app.ui.receiver.nameRes
import es.topophone.app.ui.receiver.tone
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.format.LengthFormat
import es.topophone.core.model.point.PrecisionControl
import es.topophone.core.model.point.SolutionType
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.survey.SolutionTypes

const val STATUS_NOTE_TAG = "collector_status_note"

fun infoPillTag(kind: InfoPillKind) = "collector_info_${kind.name}"

/** Lo que condiciona la medición y se enseña como pastilla informativa (S060/RF-14). */
enum class InfoPillKind { PRECISION, BASE_SHIFT, LOCALIZATION }

object InfoPills {
    /**
     * Las pastillas que tocan ahora, en orden: solo salen si el ajuste no es el de por defecto (solo FIX y sin límites), el base shift
     * está activo o la localización está activa; con los ajustes por defecto no sale ninguna (S060/RF-14, RF-19).
     */
    fun kinds(precision: PrecisionControl, baseShiftName: String?, localized: Boolean): List<InfoPillKind> = buildList {
        if (!precision.admitted.onlyFix ||
            precision.horizontalLimitM != null ||
            precision.verticalLimitM != null
        ) {
            add(InfoPillKind.PRECISION)
        }
        if (baseShiftName != null) add(InfoPillKind.BASE_SHIFT)
        if (localized) add(InfoPillKind.LOCALIZATION)
    }
}

/** Una nota de la línea de estado: lo que pasa con la solución o la precisión, sin bloquear «Medir» (S060/RF-17b). */
sealed interface StatusNote {
    data class NotAdmitted(val admitted: Set<SolutionType>) : StatusNote

    data class PrecisionOver(val vertical: Boolean, val valueM: Double, val limitM: Double) : StatusNote
}

object StatusNotes {
    /** Las notas que tocan, por prioridad: solución no admitida, precisión H y precisión V. Sin posición no hay ninguna. */
    fun of(live: LiveQuality?, precision: PrecisionControl): List<StatusNote> {
        if (live == null || live.solution == SolutionStatus.NONE) return emptyList()
        return buildList {
            if (SolutionTypes.of(live.solution) !in precision.admitted) {
                add(StatusNote.NotAdmitted(precision.admitted.types))
            } else {
                val h = live.horizontalM
                val hLimit = precision.horizontalLimitM
                if (h != null && hLimit != null && h > hLimit) add(StatusNote.PrecisionOver(false, h, hLimit))
                val v = live.verticalM
                val vLimit = precision.verticalLimitM
                if (v != null && vLimit != null && v > vLimit) add(StatusNote.PrecisionOver(true, v, vLimit))
            }
        }
    }
}

/**
 * La línea de estado: la solución con su color y su nombre, y las precisiones H y V; debajo, como mucho una nota en texto neutro
 * (S060/RF-8, RF-17b, RF-18). El color acompaña al texto, nunca lo sustituye (RNF-T8).
 */
@Composable
fun StatusLine(live: LiveQuality?, unit: LinearUnit, precision: PrecisionControl, modifier: Modifier = Modifier) {
    if (live == null) return
    val locale = uiLocale()
    val symbol = unitSymbol(unit)
    val noAccuracy = stringResource(R.string.collector_no_accuracy)

    fun length(valueM: Double?) = valueM?.let { "${LengthFormat.inUnit(it, unit, locale)} $symbol" } ?: noAccuracy
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(12.dp).background(LocalStatusPalette.current.colorsFor(live.solution.tone()).container, CircleShape))
            Text(
                stringResource(
                    R.string.collector_live,
                    stringResource(live.solution.nameRes()),
                    length(live.horizontalM),
                    length(live.verticalM),
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics(mergeDescendants = true) {}.testTag(COLLECTOR_LIVE_TAG),
            )
        }
        StatusNotes.of(live, precision).firstOrNull()?.let { note ->
            val text = when (note) {
                is StatusNote.NotAdmitted ->
                    stringResource(R.string.field_note_waiting, note.admitted.sorted().joinToString(", ") { it.name })
                is StatusNote.PrecisionOver -> stringResource(
                    R.string.field_note_precision,
                    stringResource(if (note.vertical) R.string.field_axis_v else R.string.field_axis_h),
                    length(note.valueM),
                    length(note.limitM),
                )
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(STATUS_NOTE_TAG))
        }
    }
}

/**
 * Las pastillas informativas: texto neutro, nunca el color de error, y al tocar una se va a donde se cambia (S060/RF-14, RF-18).
 * No dibuja nada si no hay ninguna que mostrar.
 */
@Composable
fun InfoPills(
    precision: PrecisionControl,
    baseShiftName: String?,
    localized: Boolean,
    unit: LinearUnit,
    modifier: Modifier = Modifier,
    onClick: (InfoPillKind) -> Unit,
) {
    val kinds = InfoPills.kinds(precision, baseShiftName, localized)
    if (kinds.isEmpty()) return
    val locale = uiLocale()
    val symbol = unitSymbol(unit)

    fun length(valueM: Double) = "${LengthFormat.inUnit(valueM, unit, locale)} $symbol"
    val pills = kinds.map { kind ->
        val label = stringResource(
            when (kind) {
                InfoPillKind.PRECISION -> R.string.field_info_precision
                InfoPillKind.BASE_SHIFT -> R.string.field_info_base_shift
                InfoPillKind.LOCALIZATION -> R.string.field_info_localization
            },
        )
        val value = when (kind) {
            InfoPillKind.PRECISION -> {
                val parts = buildList {
                    if (!precision.admitted.onlyFix) {
                        add(
                            stringResource(
                                R.string.field_info_precision_admits,
                                precision.admitted.types.sorted().joinToString(", ") {
                                    it.name
                                },
                            ),
                        )
                    }
                    precision.horizontalLimitM?.let { add(stringResource(R.string.field_info_precision_limit_h, length(it))) }
                    precision.verticalLimitM?.let { add(stringResource(R.string.field_info_precision_limit_v, length(it))) }
                }
                parts.joinToString(" · ")
            }
            InfoPillKind.BASE_SHIFT -> baseShiftName.orEmpty()
            InfoPillKind.LOCALIZATION -> stringResource(R.string.field_info_localization_active)
        }
        SettingPillData(label, value, infoPillTag(kind)) { onClick(kind) }
    }
    SettingsRow(pills, modifier)
}

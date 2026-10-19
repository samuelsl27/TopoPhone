package es.topophone.app.ui.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.StatusToneKey
import es.topophone.core.designsystem.theme.LocalStatusPalette
import es.topophone.core.designsystem.theme.StatusTone
import es.topophone.core.gnss.nmea.Constellation
import es.topophone.core.gnss.nmea.SkyView
import es.topophone.core.gnss.signal.Band
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.gnss.signal.SatelliteNumber
import es.topophone.core.survey.BaseState
import es.topophone.receiver.session.ConnectionState
import java.util.Locale

const val SNR_BASE_UNAVAILABLE_TAG = "snr_base_unavailable"

fun snrGroupTag(constellation: Constellation, band: Band?) = "snr_group_${constellation.name}_${band?.name ?: "none"}"

fun snrRowTag(satellite: SatelliteId, band: Band?) = "snr_row_${satellite.label}_${band?.name ?: "none"}"

fun snrBarTag(satellite: SatelliteId, band: Band?, base: Boolean) = snrRowTag(satellite, band) + if (base) "_base" else "_rover"

/** Una fila del gráfico: la SNR del rover (`null` sin señal) y la de la base (`null` si no la hay) de un satélite. */
data class SnrRow(val satellite: SatelliteId, val rover: Int?, val roverInView: Boolean, val base: Double?)

/** Las filas de una constelación y una banda (`null`: sin banda). */
data class SnrGroup(val constellation: Constellation, val band: Band?, val rows: List<SnrRow>)

/**
 * Filas del gráfico de SNR (plan de S015, A9): por constelación y banda, y en cada grupo por número de satélite. Las de
 * solo base salen si la base ve un satélite o una banda que el rover no; la base solo cuenta si su SNR está vigente.
 */
fun snrGroups(sky: SkyView?, base: BaseState): List<SnrGroup> {
    val rover = sky?.roverSnr.orEmpty()
    val baseSnr = if (base.snrAvailable) base.snr else emptyMap()
    return (rover.keys + baseSnr.keys)
        .groupBy { it.satellite.constellation to it.band }
        .entries
        .sortedWith(compareBy({ it.key.first.ordinal }, { it.key.second?.ordinal ?: Int.MAX_VALUE }))
        .map { (group, signals) ->
            SnrGroup(
                group.first,
                group.second,
                signals.sortedBy { it.satellite.number }.map { SnrRow(it.satellite, rover[it], it in rover, baseSnr[it]) },
            )
        }
}

/** Tramo de una SNR: menos de 35 dB-Hz, de 35 a menos de 45, y 45 o más (S015/RF-9). */
fun snrTone(dbHz: Double): StatusTone = when {
    dbHz < LOW_DB_HZ -> StatusTone.NEGATIVE
    dbHz < GOOD_DB_HZ -> StatusTone.WARNING
    else -> StatusTone.POSITIVE
}

private const val LOW_DB_HZ = 35.0
private const val GOOD_DB_HZ = 45.0

/** Una barra llena es 60 dB-Hz. */
private const val FULL_SCALE_DB_HZ = 60.0

/** SNR del rover y de la base por satélite y banda (S015/RF-7 a RF-10). */
@Composable
internal fun SnrCard(state: ReceiverUiState) {
    val snapshot = state.snapshot
    if (snapshot.connection !is ConnectionState.Receiving || snapshot.sky == null) return
    val groups = remember(snapshot.sky, snapshot.base) { snrGroups(snapshot.sky, snapshot.base) }
    ReceiverCard(stringResource(R.string.snr_title)) {
        val locale = uiLocale()
        Text(stringResource(R.string.snr_legend), style = MaterialTheme.typography.bodyMedium)
        if (!snapshot.base.snrAvailable) {
            Text(stringResource(R.string.snr_base_unavailable), modifier = Modifier.testTag(SNR_BASE_UNAVAILABLE_TAG)) // RF-10
        }
        val noBand = stringResource(R.string.snr_no_band)
        groups.forEach { group ->
            val constellation = stringResource(
                R.string.status_constellation,
                stringResource(group.constellation.nameRes()),
                SatelliteNumber.letter(group.constellation),
            )
            Text(
                stringResource(R.string.snr_group, constellation, group.band?.label ?: noBand),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp).testTag(snrGroupTag(group.constellation, group.band)),
            )
            group.rows.forEach { SnrRowView(it, group.band, locale) }
        }
    }
}

@Composable
private fun SnrRowView(row: SnrRow, band: Band?, locale: Locale) {
    val roverText = row.rover?.toString()
    val baseText = row.base?.let { formatBaseSnr(it, locale) }
    // TalkBack lee la fila entera: satélite, banda y las dos SNR (RNF-T8).
    val description = stringResource(
        R.string.snr_row_description,
        row.satellite.label,
        band?.label ?: stringResource(R.string.snr_no_band),
        roverText?.let { stringResource(R.string.snr_row_rover, it) } ?: stringResource(R.string.snr_row_rover_none),
        baseText?.let { stringResource(R.string.snr_row_base, it) } ?: stringResource(R.string.snr_row_base_none),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = description }
            .testTag(snrRowTag(row.satellite, band)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.satellite.label, fontFamily = FontFamily.Monospace, modifier = Modifier.width(48.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (row.rover != null &&
                roverText != null
            ) {
                SnrBar(row.rover.toDouble(), roverText, snrBarTag(row.satellite, band, base = false))
            }
            if (row.base != null && baseText != null) SnrBar(row.base, baseText, snrBarTag(row.satellite, band, base = true))
        }
    }
}

/** Barra con el color de su tramo y su valor escrito (S015/RF-9). */
@Composable
private fun SnrBar(dbHz: Double, label: String, tag: String) {
    val tone = snrTone(dbHz)
    val colors = LocalStatusPalette.current.colorsFor(tone)
    Row(
        modifier = Modifier.fillMaxWidth().semantics { this[StatusToneKey] = tone }.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f).height(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((dbHz / FULL_SCALE_DB_HZ).toFloat().coerceIn(MIN_FRACTION, 1f))
                    .fillMaxHeight()
                    .background(colors.container, MaterialTheme.shapes.extraSmall),
            )
        }
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.width(52.dp).padding(start = 8.dp),
        )
    }
}

private const val MIN_FRACTION = 0.02f

/** La SNR de la base: entera en MSM4 y MSM5, con un decimal en MSM6 y MSM7. */
private fun formatBaseSnr(dbHz: Double, locale: Locale): String =
    if (dbHz % 1.0 == 0.0) String.format(locale, "%.0f", dbHz) else String.format(locale, "%.1f", dbHz)

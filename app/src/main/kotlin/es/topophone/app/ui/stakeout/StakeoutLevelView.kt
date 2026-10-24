package es.topophone.app.ui.stakeout

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import es.topophone.app.R
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.LevelView
import kotlin.math.abs

const val STAKEOUT_LEVEL_TAG = "stakeout_level"
const val STAKEOUT_LEVEL_TEXT_TAG = "stakeout_level_text"
const val STAKEOUT_LEVEL_UNAVAILABLE_TAG = "stakeout_level_unavailable"

/**
 * El nivel vertical del replanteo (S019/RF-7, RF-8): un tubo con la banda de la tolerancia vertical en el centro y una burbuja
 * que sube en desmonte y baja en terraplén, a la escala de [LevelUi.radiusM]. En cota, la banda y la burbuja se pintan en verde
 * (y el texto lo dice, RNF-T8). Un `Canvas` aparte del mapa, con su descripción para TalkBack.
 */
@Composable
internal fun StakeoutLevelView(level: LevelUi, numbers: Numbers, modifier: Modifier = Modifier) {
    val onGrade = level.state == LevelState.ON_GRADE
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = GRID_ALPHA)
    val band = if (onGrade) InsideGreen else MaterialTheme.colorScheme.primary
    val bubble = if (onGrade) InsideGreen else MaterialTheme.colorScheme.error
    val bubblePosition = LevelView.bubble(level.dzM, level.radiusM)
    val bandFraction = LevelView.bandFraction(level.toleranceM, level.radiusM)
    val description = levelDescription(level, numbers)
    Canvas(modifier = modifier.semantics { contentDescription = description }.testTag(STAKEOUT_LEVEL_TAG)) {
        val tubeWidth = size.width * TUBE_WIDTH
        val left = (size.width - tubeWidth) / 2f
        val radius = tubeWidth / 2f
        val usable = size.height / 2f - radius // el centro de la burbuja no sale del tubo
        val centerY = size.height / 2f
        // El tubo.
        drawRoundRect(grid, Offset(left, 0f), Size(tubeWidth, size.height), CornerRadius(radius), style = Stroke(width = 2f))
        // La banda de la tolerancia, centrada: de -tolerancia a +tolerancia.
        val bandHalf = (bandFraction * size.height / 2f).toFloat()
        drawRect(band.copy(alpha = BAND_ALPHA), Offset(left, centerY - bandHalf), Size(tubeWidth, bandHalf * 2f))
        drawLine(band, Offset(left, centerY - bandHalf), Offset(left + tubeWidth, centerY - bandHalf), strokeWidth = 3f)
        drawLine(band, Offset(left, centerY + bandHalf), Offset(left + tubeWidth, centerY + bandHalf), strokeWidth = 3f)
        // La burbuja: arriba es desmonte.
        drawCircle(bubble, radius * BUBBLE, Offset(size.width / 2f, centerY - (bubblePosition * usable).toFloat()))
    }
}

/** "Desmonte 3,250 m", "Terraplén 0,040 m" o "En cota": el valor sin signo, en la unidad del proyecto (S019/RF-6, RF-8). */
@Composable
internal fun StakeoutLevelReading(level: LevelUi, numbers: Numbers, modifier: Modifier = Modifier) {
    val onGrade = level.state == LevelState.ON_GRADE
    Text(
        text = when (level.state) {
            LevelState.CUT -> stringResource(R.string.stakeout_level_cut, numbers.length(abs(level.dzM)))
            LevelState.FILL -> stringResource(R.string.stakeout_level_fill, numbers.length(abs(level.dzM)))
            LevelState.ON_GRADE -> stringResource(R.string.stakeout_level_on_grade)
        },
        color = if (onGrade) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.semantics { if (onGrade) liveRegion = LiveRegionMode.Polite }.testTag(STAKEOUT_LEVEL_TEXT_TAG),
    )
}

/** Por qué no hay nivel vertical aunque la guía esté encendida (S019/RF-5). */
@Composable
fun StakeoutLevelUnavailable(reason: LevelUnavailable, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(
            when (reason) {
                LevelUnavailable.NO_DESIGN_ELEVATION -> R.string.stakeout_level_no_elevation
                LevelUnavailable.TIP_WITHOUT_HEIGHT -> R.string.stakeout_level_no_tip_height
            },
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.testTag(STAKEOUT_LEVEL_UNAVAILABLE_TAG),
    )
}

@Composable
private fun levelDescription(level: LevelUi, numbers: Numbers): String = when (level.state) {
    LevelState.CUT -> stringResource(R.string.stakeout_level_description_cut, numbers.length(abs(level.dzM)))
    LevelState.FILL -> stringResource(R.string.stakeout_level_description_fill, numbers.length(abs(level.dzM)))
    LevelState.ON_GRADE -> stringResource(R.string.stakeout_level_description_on_grade)
}

private const val TUBE_WIDTH = 0.6f
private const val BUBBLE = 0.8f
private const val GRID_ALPHA = 0.35f
private const val BAND_ALPHA = 0.30f

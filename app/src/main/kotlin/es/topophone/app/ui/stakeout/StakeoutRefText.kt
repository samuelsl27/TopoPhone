package es.topophone.app.ui.stakeout

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.survey.LevelState
import es.topophone.core.survey.StakeoutLevel
import kotlin.math.abs

/**
 * El estado del nivel de un replanteo guardado (S019/RF-15): con la tolerancia vertical con la que se midió; sin ella (un
 * replanteo de antes de S019, RF-26), solo por el signo de la diferencia de cota. Null si no hay diferencia de cota.
 */
internal fun StakeoutRef.levelState(): LevelState? = dHeightM?.let { StakeoutLevel.classify(it, toleranceVM ?: 0.0) }

/** "Desmonte 0,120 m", "Terraplén 0,500 m" o "En cota" de un replanteo guardado; null sin diferencia de cota (S019/RF-15). */
@Composable
internal fun StakeoutRef.levelText(length: (Double) -> String): String? {
    val dz = dHeightM ?: return null
    return when (levelState()) {
        LevelState.CUT -> stringResource(R.string.stakeout_level_cut, length(abs(dz)))
        LevelState.FILL -> stringResource(R.string.stakeout_level_fill, length(abs(dz)))
        LevelState.ON_GRADE -> stringResource(R.string.stakeout_level_on_grade)
        null -> null
    }
}

/** "Dentro de la tolerancia", "Fuera de la tolerancia" o "Sin evaluar", siempre con texto (S019/RF-15, RF-26; RNF-T8). */
@Composable
internal fun StakeoutRef.withinText(): String = stringResource(
    when (withinTolerance) {
        true -> R.string.stakeout_inside
        false -> R.string.stakeout_outside
        null -> R.string.stakeout_not_evaluated
    },
)

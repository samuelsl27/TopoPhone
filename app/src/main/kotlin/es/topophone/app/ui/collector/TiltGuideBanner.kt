package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.survey.TiltGuide

const val TILT_GUIDE_TAG = "tilt_guide"
const val TILT_GUIDE_FIGURE_EIGHT_TAG = "tilt_guide_figure_eight"

/** El texto de la guía de cada estado o aviso del ETC (S016/RF-12). */
internal fun TiltGuide.textRes(): Int = when (this) {
    TiltGuide.NO_TILT -> R.string.tilt_guide_no_tilt
    TiltGuide.FATAL -> R.string.tilt_guide_fatal
    TiltGuide.SETUP -> R.string.tilt_guide_setup
    TiltGuide.ALIGNMENT -> R.string.tilt_guide_alignment
    TiltGuide.FAST_MOTION -> R.string.tilt_guide_fast_motion
    TiltGuide.BAD_GNSS -> R.string.tilt_guide_bad_gnss
    TiltGuide.FILTER_FAULT -> R.string.tilt_guide_filter_fault
}

/** Con el movimiento en ocho se ve cómo hacerlo, no solo se lee (S016/RF-12). */
private val TiltGuide.showsFigureEight: Boolean get() = this == TiltGuide.ALIGNMENT || this == TiltGuide.FILTER_FAULT

/**
 * La guía de la inclinación en el recolector (S016/RF-12): qué hacer según el estado o el aviso del ETC, con la ilustración
 * propia del ocho cuando hay que alinear. Con texto e ilustración, nunca solo con color (RNF-T8).
 */
@Composable
internal fun TiltGuideBanner(guide: TiltGuide, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(TILT_GUIDE_TAG),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(guide.textRes()),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            if (guide.showsFigureEight) {
                Icon(
                    painterResource(R.drawable.ic_tilt_figure_eight),
                    contentDescription = stringResource(R.string.tilt_guide_figure_eight_description),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.testTag(TILT_GUIDE_FIGURE_EIGHT_TAG),
                )
            }
        }
    }
}

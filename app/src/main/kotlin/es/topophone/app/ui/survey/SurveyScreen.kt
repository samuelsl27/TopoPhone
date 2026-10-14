package es.topophone.app.ui.survey

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.EmptyState
import es.topophone.core.model.preferences.ThemeState

/** Sección Levantamiento. Sin funciones todavía: estado vacío (S001/RF-4). */
@Composable
fun SurveyScreen(themeState: ThemeState, onToggleSun: () -> Unit) {
    SectionScaffold(title = stringResource(R.string.nav_survey), themeState = themeState, onToggleSun = onToggleSun) { padding ->
        EmptyState(
            title = stringResource(R.string.nav_survey),
            description = stringResource(R.string.survey_empty_description),
            modifier = Modifier.padding(padding),
        )
    }
}

package es.topophone.app.ui.projects

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import es.topophone.app.R
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.TerrainView

const val TERRAIN_MARK_TAG = "terrain_mark"

/** Lo que las pantallas de un proyecto usan para mostrar sus coordenadas y distancias: en cuadrícula la identidad (S058/RF-9). */
@Composable
fun rememberTerrainView(project: Project): TerrainView =
    remember(project.crs, project.terrain) { TerrainView.of(project.terrain, project.crs) ?: TerrainView.GRID }

/** Como [rememberTerrainView] para un plano y un SC que todavía no son de un proyecto guardado (el formulario). */
@Composable
fun rememberTerrainView(plane: TerrainPlane?, crs: ProjectCrs): TerrainView =
    remember(plane, crs) { TerrainView.of(plane, crs) ?: TerrainView.GRID }

/** «Terreno» junto a las coordenadas y las distancias de un proyecto en terreno; con cuadrícula no pinta nada (S058/RF-9). */
@Composable
fun TerrainMark(view: TerrainView, tag: String = TERRAIN_MARK_TAG) {
    if (!view.isTerrain) return
    Text(stringResource(R.string.terrain_ground), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag(tag))
}

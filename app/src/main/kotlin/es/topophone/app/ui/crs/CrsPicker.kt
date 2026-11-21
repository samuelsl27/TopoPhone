package es.topophone.app.ui.crs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import es.topophone.app.R
import es.topophone.app.ui.SectionScaffold
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.geodesy.catalog.Country
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.CrsEntry
import es.topophone.core.geodesy.catalog.CrsKind
import es.topophone.core.geodesy.catalog.CrsSearch
import es.topophone.core.geodesy.catalog.CrsShortcutList
import es.topophone.core.geodesy.catalog.CrsShortcuts
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.crs.CrsShortcutRepository
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

const val CRS_SEARCH_TAG = "crs_search"
const val CRS_COUNTRY_TAG = "crs_country"
const val CRS_NO_RESULTS_TAG = "crs_no_results"

fun crsRowTag(epsg: Int) = "crs_row_$epsg"

fun crsFavoriteTag(epsg: Int) = "crs_favorite_$epsg"

fun crsCountryTag(iso: String?) = "crs_country_${iso ?: "all"}"

/** Cuántos resultados se dibujan a la vez: la lista completa puede tener más de 5.000 (S034/RF-2). */
const val CRS_MAX_SHOWN = 100

/** Lo que muestra el selector de SC de la biblioteca (S034/RF-1 a RF-8). */
data class CrsPickerUiState(
    val query: String = "",
    val countryIso: String? = null,
    val results: List<CrsEntry> = emptyList(),
    /** Cuántos SC encajan en total, aunque solo se dibujen [CRS_MAX_SHOWN]. */
    val total: Int = 0,
    val shortcuts: CrsShortcutList = CrsShortcutList(emptyList(), emptyList()),
    val favoriteCodes: Set<Int> = emptySet(),
    val countries: List<Country> = emptyList(),
) {
    /** Los atajos solo se ven antes de buscar y sin filtrar por país (S034/RF-6). */
    val showShortcuts: Boolean get() = query.isBlank() && countryIso == null && !shortcuts.isEmpty
}

/**
 * Búsqueda por país, código y nombre en la biblioteca de SC, con favoritos y recientes (S034/RF-1 a RF-8). La búsqueda va en segundo plano:
 * son más de 5.000 SC (RNF-T13).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class CrsPickerViewModel @Inject constructor(private val shortcutsRepository: CrsShortcutRepository) : ViewModel() {
    private val catalog = CrsCatalog.bundled
    private val search = CrsSearch(catalog)
    private val shortcuts = CrsShortcuts(catalog)
    private val query = MutableStateFlow("")
    private val countryIso = MutableStateFlow<String?>(null)

    val state: StateFlow<CrsPickerUiState> = combine(
        query,
        countryIso,
        shortcutsRepository.favorites(),
        shortcutsRepository.recents(),
    ) { text, iso, favorites, recents -> Inputs(text, iso, favorites, recents) }
        .mapLatest { (text, iso, favorites, recents) ->
            val all = search.search(text, iso)
            CrsPickerUiState(
                query = text,
                countryIso = iso,
                results = all.take(CRS_MAX_SHOWN),
                total = all.size,
                shortcuts = shortcuts.resolve(favorites, recents),
                favoriteCodes = favorites.toSet(),
                countries = search.countries,
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), CrsPickerUiState(countries = search.countries))

    private data class Inputs(val text: String, val iso: String?, val favorites: List<Int>, val recents: List<Int>)

    fun onQuery(text: String) {
        query.value = text
    }

    fun onCountry(iso: String?) {
        countryIso.value = iso
    }

    fun toggleFavorite(epsg: Int, favorite: Boolean) {
        viewModelScope.launch { shortcutsRepository.setFavorite(epsg, favorite) }
    }

    /** Anota que se eligió [epsg] en un proyecto, para ofrecerlo entre los recientes (S034/RF-8). */
    fun markUsed(epsg: Int) {
        viewModelScope.launch { shortcutsRepository.markUsed(epsg) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Acciones del selector. */
class CrsPickerActions(
    val onQuery: (String) -> Unit = {},
    val onCountry: (String?) -> Unit = {},
    val onFavorite: (epsg: Int, favorite: Boolean) -> Unit = { _, _ -> },
    /** Abre la ficha del SC (S034/RF-9). */
    val onOpen: (CrsEntry) -> Unit = {},
    val onBack: () -> Unit = {},
)

/** El selector: busca, filtra por país y ofrece favoritos y recientes (S034/RF-1 a RF-8). Zonas táctiles de 56 dp (RNF-T8). */
@Composable
fun CrsPickerScreen(themeState: ThemeState, onToggleSun: () -> Unit, state: CrsPickerUiState, actions: CrsPickerActions) {
    SectionScaffold(
        title = stringResource(R.string.crs_picker_title),
        themeState = themeState,
        onToggleSun = onToggleSun,
        onNavigateBack = actions.onBack,
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = actions.onQuery,
                label = { Text(stringResource(R.string.crs_search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(CRS_SEARCH_TAG),
            )
            CountryFilter(state, actions.onCountry)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.showShortcuts) {
                    if (state.shortcuts.favorites.isNotEmpty()) {
                        item { SectionTitle(R.string.crs_favorites) }
                        items(state.shortcuts.favorites, key = { "f${it.epsg}" }) { crs -> CrsRow(crs, state, actions) }
                    }
                    if (state.shortcuts.recents.isNotEmpty()) {
                        item { SectionTitle(R.string.crs_recents) }
                        items(state.shortcuts.recents, key = { "r${it.epsg}" }) { crs -> CrsRow(crs, state, actions) }
                    }
                    item { SectionTitle(R.string.crs_all) }
                }
                if (state.results.isEmpty()) {
                    item { Text(stringResource(R.string.crs_no_results), modifier = Modifier.padding(16.dp).testTag(CRS_NO_RESULTS_TAG)) }
                } else {
                    items(state.results, key = { "s${it.epsg}" }) { crs -> CrsRow(crs, state, actions) }
                    if (state.total > state.results.size) {
                        item {
                            Text(
                                stringResource(R.string.crs_more_results, state.results.size, state.total),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun CountryFilter(state: CrsPickerUiState, onCountry: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = state.countries.firstOrNull { it.iso == state.countryIso }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(CRS_COUNTRY_TAG)) {
        Text(selected?.nameEs ?: stringResource(R.string.crs_country_all))
    }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.crs_country_all)) },
            onClick = {
                open = false
                onCountry(null)
            },
            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(crsCountryTag(null)),
        )
        state.countries.forEach { country ->
            DropdownMenuItem(
                text = { Text(country.nameEs) },
                onClick = {
                    open = false
                    onCountry(country.iso)
                },
                modifier = Modifier.heightIn(min = MinTouchTarget).testTag(crsCountryTag(country.iso)),
            )
        }
    }
}

@Composable
private fun CrsRow(crs: CrsEntry, state: CrsPickerUiState, actions: CrsPickerActions) {
    val favorite = crs.epsg in state.favoriteCodes
    val countryNames = crs.countries.mapNotNull { iso -> state.countries.firstOrNull { it.iso == iso }?.nameEs }.take(3).joinToString(", ")
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget).clickable { actions.onOpen(crs) }.testTag(crsRowTag(crs.epsg)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(crs.name, style = MaterialTheme.typography.bodyLarge)
            val kind = stringResource(if (crs.kind == CrsKind.PROJECTED) R.string.crs_kind_projected else R.string.crs_kind_geographic)
            Text(
                listOf("EPSG:${crs.epsg}", countryNames, kind).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        val label = stringResource(if (favorite) R.string.crs_favorite_remove else R.string.crs_favorite_add, crs.name)
        IconButton(
            onClick = { actions.onFavorite(crs.epsg, !favorite) },
            modifier = Modifier.size(MinTouchTarget).testTag(crsFavoriteTag(crs.epsg)).semantics { contentDescription = label },
        ) {
            Icon(painterResource(if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline), contentDescription = null)
        }
    }
    HorizontalDivider()
}

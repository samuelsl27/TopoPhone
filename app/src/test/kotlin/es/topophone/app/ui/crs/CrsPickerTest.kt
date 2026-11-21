package es.topophone.app.ui.crs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.geodesy.catalog.CrsEntry
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.data.crs.CrsShortcutRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Atajos en memoria para probar sin base de datos. */
class FakeCrsShortcutRepository : CrsShortcutRepository {
    val favorites = MutableStateFlow<List<Int>>(emptyList())
    val recents = MutableStateFlow<List<Int>>(emptyList())

    override fun favorites() = favorites

    override fun recents() = recents

    override suspend fun setFavorite(epsg: Int, favorite: Boolean) {
        favorites.value = if (favorite) listOf(epsg) + favorites.value.filter { it != epsg } else favorites.value - epsg
    }

    override suspend fun markUsed(epsg: Int) {
        recents.value = (listOf(epsg) + recents.value.filter { it != epsg }).take(10)
    }
}

/** El selector de SC de la biblioteca: búsqueda, país, favoritos y recientes (S034/RF-1 a RF-8). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CrsPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val repository = FakeCrsShortcutRepository()
    private lateinit var vm: CrsPickerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm = CrsPickerViewModel(repository)
        CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun await(predicate: (CrsPickerUiState) -> Boolean): CrsPickerUiState =
        runBlocking { withTimeout(10_000) { vm.state.first(predicate) } }

    @Test
    fun `S034 RF-2 el codigo EPSG exacto sale el primero y los resultados traen nombre, codigo y pais`() {
        vm.onQuery("25830")

        val state = await { it.query == "25830" && it.results.isNotEmpty() }

        state.results.first().epsg shouldBe 25830
        state.results.first().countries shouldContain "ES"
        state.total shouldBeGreaterThan 0
    }

    @Test
    fun `S034 RF-2 RF-5 buscar un pais y filtrar por pais dejan solo sus SC con los de Espana siempre delante`() {
        vm.onCountry("FR")
        val french = await { it.countryIso == "FR" && it.results.isNotEmpty() }

        french.results.all { "FR" in it.countries } shouldBe true

        vm.onCountry(null)
        vm.onQuery("utm")
        val all = await { it.countryIso == null && it.query == "utm" && it.results.isNotEmpty() }
        all.results.first().countries shouldContain "ES"
    }

    @Test
    fun `S034 RF-3 se dibujan como mucho cien resultados y se dice cuantos hay`() {
        vm.onQuery("")

        val state = await { it.results.isNotEmpty() }

        state.results.size shouldBe CRS_MAX_SHOWN
        (state.total > CRS_MAX_SHOWN) shouldBe true
    }

    @Test
    fun `S034 RF-6 sin buscar salen primero los favoritos y luego los recientes`() {
        vm.toggleFavorite(2154, true)
        vm.markUsed(25830)

        val state = await { it.shortcuts.favorites.isNotEmpty() && it.shortcuts.recents.isNotEmpty() }

        state.showShortcuts shouldBe true
        state.shortcuts.favorites.map(CrsEntry::epsg) shouldBe listOf(2154)
        state.shortcuts.recents.map(CrsEntry::epsg) shouldBe listOf(25830)
        vm.onQuery("lambert")
        await { it.query == "lambert" }.showShortcuts shouldBe false
    }

    @Test
    fun `S034 RF-7 una estrella marca y desmarca el favorito`() {
        var state by mutableStateOf(CrsPickerUiState())
        val chosen = mutableListOf<Pair<Int, Boolean>>()
        vm.onQuery("25830")
        state = await { it.query == "25830" && it.results.isNotEmpty() }
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CrsPickerScreen(ThemeState.DEFAULT, {}, state, CrsPickerActions(onFavorite = { epsg, on -> chosen += epsg to on }))
            }
        }

        compose.onNodeWithTag(crsFavoriteTag(25830)).assertIsDisplayed().performClick()
        chosen shouldBe listOf(25830 to true)
        compose.onNode(hasContentDescription("Marcar ${state.results.first().name} como favorito", substring = false)).assertIsDisplayed()
    }

    @Test
    fun `S034 RF-1 el campo de busqueda y el filtro de pais estan en la pantalla y buscar sin resultados lo dice`() {
        var state by mutableStateOf(CrsPickerUiState(query = "zzzzzz"))
        val typed = mutableListOf<String>()
        compose.setContent {
            TopoPhoneTheme(EffectiveTheme.LIGHT) {
                CrsPickerScreen(ThemeState.DEFAULT, {}, state, CrsPickerActions(onQuery = { typed += it }))
            }
        }

        compose.onNodeWithTag(CRS_NO_RESULTS_TAG).assertIsDisplayed()
        compose.onNodeWithTag(CRS_COUNTRY_TAG).assertIsDisplayed().assertTextContains("Todos los países")
        compose.onNodeWithTag(CRS_SEARCH_TAG).performTextInput("a")
        typed.size shouldBe 1
        state = state.copy(query = "")
    }
}

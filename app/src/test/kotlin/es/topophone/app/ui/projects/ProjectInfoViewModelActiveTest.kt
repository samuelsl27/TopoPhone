package es.topophone.app.ui.projects

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/** «Datos del proyecto» sabe qué está activo y lo actualiza al activar o desactivar un elemento sin volver a abrir (S059/RF-18, RF-19, RF-22). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ProjectInfoViewModelActiveTest {

    private class Sources(val flow: MutableStateFlow<ActiveInputs>) : ActiveSources {
        override fun inputs(projectId: String): Flow<ActiveInputs> = flow
    }

    private fun withViewModel(
        sources: ActiveSources,
        block: (ProjectInfoViewModel, suspend (predicate: (ProjectInfoUiState) -> Boolean) -> ProjectInfoUiState) -> Unit,
    ) {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val repository = RoomProjectRepository(db)
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Muralla")) as SaveResult.Saved).id }
            val vm = ProjectInfoViewModel(repository, FakePointRepository(), activeSources = sources)
            vm.start(id)
            val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
            try {
                block(vm) { predicate -> withTimeout(5_000) { vm.state.first(predicate) } }
            } finally {
                collector.cancel()
            }
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `S059 RF-18 el ViewModel da lo activo en su orden con el estado de cada elemento`() {
        val flow = MutableStateFlow(ActiveInputs(baseShiftName = "Vértice 4", surfaceName = "Terreno", layersVisible = 1, layersTotal = 2))
        withViewModel(Sources(flow)) { _, await ->
            runBlocking {
                val state = await { it.project != null && it.active.isNotEmpty() }
                state.active.map { it.kind } shouldBe listOf(ActiveKind.BASE_SHIFT, ActiveKind.SURFACE, ActiveKind.LAYERS)
                state.active.first().status shouldBe ActiveStatus.Name("Vértice 4")
            }
        }
    }

    @Test
    fun `S059 RF-22 al activar o desactivar un elemento la lista cambia sin volver a abrir`() {
        val flow = MutableStateFlow(ActiveInputs())
        withViewModel(Sources(flow)) { _, await ->
            runBlocking {
                await { it.project != null }.active.shouldBeEmpty()

                flow.value = ActiveInputs(localizationActive = true, localizationRmsM = 0.011)
                await { it.active.isNotEmpty() }.active.single().status shouldBe ActiveStatus.Rms(0.011)

                flow.value = ActiveInputs()
                await { it.active.isEmpty() }.active.shouldBeEmpty()
            }
        }
    }
}

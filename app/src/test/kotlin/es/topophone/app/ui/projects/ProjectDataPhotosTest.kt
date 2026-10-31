package es.topophone.app.ui.projects

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.ui.map.FakePointRepository
import es.topophone.core.designsystem.theme.TopoPhoneTheme
import es.topophone.core.model.point.PhotoStats
import es.topophone.core.model.preferences.EffectiveTheme
import es.topophone.core.model.preferences.ThemeState
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.photo.PhotoRepository
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Los datos del proyecto dicen cuántas fotos tiene y cuánto ocupan (S017b/RF-27). Interfaz en es-ES. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class ProjectDataPhotosTest {

    @get:Rule
    val compose = createComposeRule()

    private val project = Project(
        id = "p1",
        name = "Calle Mayor 12",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DECIMAL_DEGREES,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 1_000,
        modifiedAtMs = 1_000,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun show(photos: PhotoStats) = compose.setContent {
        TopoPhoneTheme(EffectiveTheme.LIGHT) {
            ProjectInfoScreen(ThemeState.DEFAULT, {}, ProjectInfoUiState(project, pointCount = 4, photos = photos), onBack = {})
        }
    }

    @Test
    fun `S017b RF-27 los datos del proyecto dicen cuantas fotos tiene y cuanto ocupan`() {
        show(PhotoStats(count = 37, bytes = 41_300_000L))

        compose.onNodeWithTag(PROJECT_PHOTOS_TAG).performScrollTo().assert(hasText("37 fotos (41,3 MB)"))
    }

    @Test
    fun `S017b RF-27 con una foto va en singular y en kilobytes si es pequena`() {
        show(PhotoStats(count = 1, bytes = 850_000L))

        compose.onNodeWithTag(PROJECT_PHOTOS_TAG).performScrollTo().assert(hasText("1 foto (850 kB)"))
    }

    @Test
    fun `S017b RF-27 sin fotos lo dice`() {
        show(PhotoStats.NONE)

        compose.onNodeWithTag(PROJECT_PHOTOS_TAG).performScrollTo().assert(hasText("Sin fotos"))
    }

    @Test
    fun `S017b RF-27 el ViewModel trae las cifras de fotos del proyecto`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), TopoPhoneDatabase::class.java).build()
        try {
            val repository = RoomProjectRepository(db)
            val id = runBlocking { (repository.create(ProjectDefaults().toDraft().copy(name = "Muralla")) as SaveResult.Saved).id }
            val photos = object : PhotoRepository by PhotoRepository.NONE {
                override fun stats(projectId: String): Flow<PhotoStats> = flowOf(PhotoStats(5, 5_000_000L))
            }
            val vm = ProjectInfoViewModel(repository, FakePointRepository(), photos)

            vm.start(id)

            val state = runBlocking {
                val collector = CoroutineScope(Dispatchers.Unconfined).launch { vm.state.collect {} }
                try {
                    withTimeout(5_000) { vm.state.first { it.project != null } }
                } finally {
                    collector.cancel()
                }
            }
            state.photos shouldBe PhotoStats(5, 5_000_000L)
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}

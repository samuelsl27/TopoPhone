package es.topophone.app.ui.sessions

import es.topophone.receiver.session.SessionLogStore
import io.kotest.matchers.shouldBe
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Lo que la lista de sesiones saca del registro (S003/RF-29, S003/RF-31). */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModelTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = Instant.parse("2026-10-15T08:30:00Z")
    private val store by lazy { SessionLogStore(folder.root, clock = { now }, freeSpaceBytes = { Long.MAX_VALUE }) }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `S003 RF-29 las filas tienen inicio, duracion, tamano, receptor y si esta en curso, de la mas reciente a la mas antigua`() {
        store.start("reach-RS3")!!.apply {
            write(ByteArray(100), 0, 100)
            now = now.plusSeconds(3_720)
            close()
        }
        now = now.plusSeconds(60)
        store.start("reach-RS3")
        val viewModel = SessionsViewModel(store)

        val rows = viewModel.state.value.rows
        rows.map { it.id } shouldBe listOf("20261015-093300", "20261015-083000")
        rows.map { it.inProgress } shouldBe listOf(true, false)
        rows.last().durationS shouldBe 3_720L
        rows.last().receiver shouldBe "reach-RS3"
        rows.last().imported shouldBe false
        rows.last().sizeBytes shouldBe store.sessions().last().sizeBytes
    }

    @Test
    fun `S003 RF-31 al borrar una sesion desaparece de la lista`() {
        store.start("reach-RS3")!!.close()
        val viewModel = SessionsViewModel(store)
        val id = viewModel.state.value.rows.single().id

        viewModel.delete(id)

        viewModel.state.value.rows shouldBe emptyList()
    }
}

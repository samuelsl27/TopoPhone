package es.topophone.data.localization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.localization.LocalizationLimits
import es.topophone.core.model.localization.LocalizationPair
import es.topophone.core.model.localization.LocalizationStatus
import es.topophone.core.model.project.ProjectDefaults
import es.topophone.data.db.TopoPhoneDatabase
import es.topophone.data.project.RoomProjectRepository
import es.topophone.data.project.SaveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** El borrador de la localización de un proyecto: un solo borrador, pares, componentes, escala fija y umbrales (S039/RF-2, RF-4, RF-5, RF-10, RF-13). */
@RunWith(AndroidJUnit4::class)
class LocalizationRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private val projects = RoomProjectRepository(db)
    private val repository = RoomLocalizationRepository(db)

    @After
    fun close() = db.close()

    private val project = runBlocking {
        projects.create(ProjectDefaults().toDraft().copy(name = "Camino de la Dehesa")).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun pair(id: String, pointId: String? = "pt-$id") =
        LocalizationPair(id, pointId, "P$id", 440_290.123_456_789, 4_474_257.000_000_001, 650.123_456_789, 1000.5, 2000.25, 650.0)

    @Test
    fun `S039 RF-2 el primer par crea el borrador con el SC y las alturas del proyecto y guarda los numeros sin redondear`() =
        runBlocking<Unit> {
            repository.draft(project).first().shouldBeNull()

            repository.addPair(project, pair("1")) shouldBe true

            val draft = repository.draft(project).first().shouldNotBeNull()
            draft.status shouldBe LocalizationStatus.DRAFT
            draft.crsText.startsWith("SPAIN|") shouldBe true
            draft.heightSystem shouldBe "ORTHOMETRIC"
            draft.fixScale shouldBe false
            draft.residualLimitM shouldBe LocalizationLimits.DEFAULT_RESIDUAL_LIMIT_M
            draft.pairs.single() shouldBe pair("1")
        }

    @Test
    fun `S039 RF-3 y RF-4 no admite un punto repetido ni mas de 50 pares`() = runBlocking<Unit> {
        repository.addPair(project, pair("1")) shouldBe true
        repository.addPair(project, pair("2", pointId = "pt-1")) shouldBe false

        for (i in 2..LocalizationLimits.MAX_PAIRS) repository.addPair(project, pair("$i")) shouldBe true
        repository.addPair(project, pair("extra")) shouldBe false

        repository.draft(project).first()!!.pairs.size shouldBe LocalizationLimits.MAX_PAIRS
    }

    @Test
    fun `S039 RF-5 excluir una componente no borra el par y confirmar lo deja contar`() = runBlocking<Unit> {
        repository.addPair(project, pair("1").copy(confirmed = false))

        repository.setComponents(project, "1", useHorizontal = false, useVertical = true)
        repository.confirmPair(project, "1")

        val saved = repository.draft(project).first()!!.pairs.single()
        saved.useHorizontal shouldBe false
        saved.useVertical shouldBe true
        saved.confirmed shouldBe true
    }

    @Test
    fun `S039 RF-2 quitar un par conserva el orden de los demas`() = runBlocking<Unit> {
        for (i in 1..3) repository.addPair(project, pair("$i"))

        repository.removePair(project, "2")

        repository.draft(project).first()!!.pairs.map { it.id } shouldBe listOf("1", "3")
    }

    @Test
    fun `S039 RF-8 y RF-13 la escala fija y los umbrales se guardan en el borrador`() = runBlocking<Unit> {
        repository.setFixScale(project, true)
        repository.setLimits(project, 0.05, 0.002)

        val draft = repository.draft(project).first().shouldNotBeNull()
        draft.fixScale shouldBe true
        draft.residualLimitM shouldBe 0.05
        draft.scaleLimit shouldBe 0.002
        repository.addPair(project, pair("1"))
        repository.draft(project).first()!!.fixScale shouldBe true
    }

    @Test
    fun `S039 RF-16 hay un solo borrador por proyecto y descartarlo lo borra con sus pares`() = runBlocking<Unit> {
        repository.addPair(project, pair("1"))
        repository.setFixScale(project, true)
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM localizations WHERE status = 'DRAFT'").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 1
        }

        repository.discardDraft(project)

        repository.draft(project).first().shouldBeNull()
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM localization_pairs").use {
            it.moveToFirst()
            it.getInt(0) shouldBe 0
        }
    }

    @Test
    fun `S039 RF-13 un proyecto que no existe no tiene borrador`() = runBlocking<Unit> {
        repository.addPair("no-existe", pair("1")) shouldBe false
        repository.draft("no-existe").first().shouldBeNull()
    }
}

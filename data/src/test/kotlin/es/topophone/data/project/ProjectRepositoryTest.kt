package es.topophone.data.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.NameError
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDraft
import es.topophone.data.db.TopoPhoneDatabase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** Repositorio de proyectos (S006/RF-1, RF-2, RF-7, RF-12, RF-14, RF-15, RF-20). */
@RunWith(AndroidJUnit4::class)
class ProjectRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, TopoPhoneDatabase::class.java).build()
    private var now = 1_792_080_000_000L
    private var nextId = 0
    private val repository = RoomProjectRepository(db, clock = { now }, newId = { "id-${++nextId}" })

    @After
    fun close() = db.close()

    private fun draft(name: String = "Calle Mayor 12") = ProjectDraft(
        name = name,
        author = "S. S.",
        description = "Levantamiento de la fachada",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = LinearUnit.METRE,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
    )

    private fun create(name: String, at: Long): String = runBlocking {
        now = at
        repository.create(draft(name)).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    private fun rootNames() = runBlocking { repository.projects(folderId = null).first().map { it.name } }

    @Test
    fun `S006 RF-12 un proyecto valido se guarda con todos sus datos`() = runBlocking<Unit> {
        val id = repository.create(draft()).shouldBeInstanceOf<SaveResult.Saved>().id

        val project = repository.project(id).shouldNotBeNull()
        project.name shouldBe "Calle Mayor 12"
        project.author shouldBe "S. S."
        project.description shouldBe "Levantamiento de la fachada"
        project.crs shouldBe ProjectCrs.ETRS89_UTM30
        project.linearUnit shouldBe LinearUnit.METRE
        project.angularUnit shouldBe AngularUnit.DMS
        project.correctionsDatum shouldBe CorrectionsDatum.ETRS89
        project.createdAtMs shouldBe now
        project.folderId shouldBe null
    }

    @Test
    fun `S006 RF-7 sin nombre o con un nombre repetido no se guarda`() = runBlocking<Unit> {
        repository.create(draft("Calle Mayor 12"))

        repository.create(draft("  ")) shouldBe SaveResult.Invalid(NameError.EMPTY)
        repository.create(draft("CALLE MAYOR 12")) shouldBe SaveResult.Invalid(NameError.DUPLICATE)
        rootNames() shouldContainExactly listOf("Calle Mayor 12")
    }

    @Test
    fun `S006 RF-7 el nombre se guarda sin espacios a los lados`() = runBlocking<Unit> {
        val id = repository.create(draft("  Parcela 23 ")).shouldBeInstanceOf<SaveResult.Saved>().id

        repository.project(id).shouldNotBeNull().name shouldBe "Parcela 23"
    }

    @Test
    fun `S006 RF-7 un proyecto de la papelera no impide reutilizar su nombre`() = runBlocking<Unit> {
        val id = create("Parcela 23", at = 1)
        db.projects().setDeletedAt(id, deletedAt = 2)

        repository.create(draft("Parcela 23")).shouldBeInstanceOf<SaveResult.Saved>()
    }

    @Test
    fun `S006 RF-1 los proyectos se listan del abierto mas recientemente al mas antiguo`() {
        val a = create("A", at = 1_000)
        create("B", at = 2_000)
        create("C", at = 3_000)
        rootNames() shouldContainExactly listOf("C", "B", "A")

        now = 4_000
        runBlocking { repository.markOpened(a) }

        rootNames() shouldContainExactly listOf("A", "C", "B")
    }

    @Test
    fun `S006 RF-2 de cada proyecto se conocen su nombre, su SC y su ultima modificacion`() = runBlocking<Unit> {
        val id = create("A", at = 1_000)
        now = 5_000
        repository.update(id, draft("A").copy(crs = ProjectCrs.ETRS89_UTM31))

        val listed = repository.projects(folderId = null).first().single()
        listed.name shouldBe "A"
        listed.crs shouldBe ProjectCrs.ETRS89_UTM31
        listed.modifiedAtMs shouldBe 5_000
    }

    @Test
    fun `S006 RF-20 la lista de una carpeta tiene sus proyectos y la raiz los que no estan en ninguna`() = runBlocking<Unit> {
        db.projects().insertFolder(ProjectFolderEntity("carpeta-1", "Obras 2026"))
        val inside = create("Dentro", at = 1_000)
        create("Fuera", at = 2_000)
        db.projects().setFolder(inside, "carpeta-1")

        repository.projects(folderId = "carpeta-1").first().map { it.name } shouldContainExactly listOf("Dentro")
        rootNames() shouldContainExactly listOf("Fuera")
    }

    @Test
    fun `S006 RF-14 al editar se cambian todos los datos con las mismas comprobaciones`() = runBlocking<Unit> {
        val a = create("A", at = 1_000)
        create("B", at = 2_000)
        val changed = draft("A2").copy(
            author = "Otro",
            description = "",
            linearUnit = LinearUnit.US_SURVEY_FOOT,
            angularUnit = AngularUnit.GON,
            correctionsDatum = CorrectionsDatum.ITRF_WGS84,
        )

        repository.update(a, draft("b")) shouldBe SaveResult.Invalid(NameError.DUPLICATE)
        repository.update(a, draft("")) shouldBe SaveResult.Invalid(NameError.EMPTY)
        repository.update(a, draft("a")) shouldBe SaveResult.Saved(a) // su propio nombre con otras mayúsculas
        repository.update(a, changed) shouldBe SaveResult.Saved(a)

        val project = repository.project(a).shouldNotBeNull()
        project.name shouldBe "A2"
        project.author shouldBe "Otro"
        project.linearUnit shouldBe LinearUnit.US_SURVEY_FOOT
        project.angularUnit shouldBe AngularUnit.GON
        project.correctionsDatum shouldBe CorrectionsDatum.ITRF_WGS84
    }

    @Test
    fun `S006 RF-15 cambiar el SC no cambia la identidad ni la fecha de creacion del proyecto`() = runBlocking<Unit> {
        val id = create("A", at = 1_000)
        now = 9_000

        repository.update(id, draft("A").copy(crs = ProjectCrs.ETRS89_UTM29))

        val project = repository.project(id).shouldNotBeNull()
        project.crs shouldBe ProjectCrs.ETRS89_UTM29
        project.createdAtMs shouldBe 1_000
        project.modifiedAtMs shouldBe 9_000
    }
}

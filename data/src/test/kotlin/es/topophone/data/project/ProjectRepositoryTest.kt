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

    private fun createFolder(name: String): String = runBlocking {
        repository.createFolder(name).shouldBeInstanceOf<SaveResult.Saved>().id
    }

    @Test
    fun `S006 RF-4 la busqueda encuentra por nombre o descripcion en todas las carpetas, sin mayusculas ni tildes`() = runBlocking<Unit> {
        val obras = createFolder("Obras 2026")
        val avila = create("Camino de Ávila", at = 1_000)
        create("Parcela 23", at = 2_000)
        val fachada = repository.create(
            draft("Calle Mayor 12").copy(description = "Fachada norte"),
        ).shouldBeInstanceOf<SaveResult.Saved>().id
        repository.moveToFolder(avila, obras)

        repository.search("AVILA").first().map { it.project.name to it.folder?.name } shouldContainExactly
            listOf("Camino de Ávila" to "Obras 2026")
        repository.search("norte").first().map { it.project.id } shouldContainExactly listOf(fachada)
        repository.search("zzz").first() shouldBe emptyList()
    }

    @Test
    fun `S006 RF-4 la busqueda no encuentra los proyectos de la papelera`() = runBlocking<Unit> {
        val id = create("Parcela 23", at = 1_000)
        db.projects().setDeletedAt(id, deletedAt = 2_000)

        repository.search("parcela").first() shouldBe emptyList()
    }

    @Test
    fun `S006 RF-16 duplicar crea una copia con sus datos en la misma carpeta y con nombre (copia)`() = runBlocking<Unit> {
        val obras = createFolder("Obras 2026")
        val original = create("Parcela 23", at = 1_000)
        repository.moveToFolder(original, obras)
        now = 5_000

        val first = repository.duplicate(original, copyLabel = "copia").shouldBeInstanceOf<SaveResult.Saved>().id
        val second = repository.duplicate(original, copyLabel = "copia").shouldBeInstanceOf<SaveResult.Saved>().id

        val copy = repository.project(first).shouldNotBeNull()
        copy.name shouldBe "Parcela 23 (copia)"
        copy.toDraft() shouldBe draft("Parcela 23 (copia)")
        copy.folderId shouldBe obras
        copy.createdAtMs shouldBe 5_000
        repository.project(second).shouldNotBeNull().name shouldBe "Parcela 23 (copia 2)"
    }

    @Test
    fun `S006 RF-17 una carpeta necesita un nombre no vacio y distinto del de las demas`() = runBlocking<Unit> {
        createFolder("Obras 2026")

        repository.createFolder(" ") shouldBe SaveResult.Invalid(NameError.EMPTY)
        repository.createFolder("obras 2026") shouldBe SaveResult.Invalid(NameError.DUPLICATE)
        repository.createFolder("Fincas").shouldBeInstanceOf<SaveResult.Saved>()
        repository.folders().first().map { it.name } shouldContainExactly listOf("Fincas", "Obras 2026")
    }

    @Test
    fun `S006 RF-18 renombrar una carpeta tiene las mismas comprobaciones`() = runBlocking<Unit> {
        val obras = createFolder("Obras 2026")
        createFolder("Fincas")

        repository.renameFolder(obras, "FINCAS") shouldBe SaveResult.Invalid(NameError.DUPLICATE)
        repository.renameFolder(obras, "") shouldBe SaveResult.Invalid(NameError.EMPTY)
        repository.renameFolder(obras, "Obras 2027") shouldBe SaveResult.Saved(obras)
        repository.folders().first().map { it.name } shouldContainExactly listOf("Fincas", "Obras 2027")
    }

    @Test
    fun `S006 RF-19 mover un proyecto a una carpeta y sacarlo de ella`() = runBlocking<Unit> {
        val obras = createFolder("Obras 2026")
        val id = create("Parcela 23", at = 1_000)

        repository.moveToFolder(id, obras)
        repository.projects(obras).first().map { it.id } shouldContainExactly listOf(id)
        rootNames() shouldBe emptyList()

        repository.moveToFolder(id, null)
        rootNames() shouldContainExactly listOf("Parcela 23")
    }

    @Test
    fun `S006 RF-21 al borrar una carpeta sus proyectos quedan fuera de las carpetas`() = runBlocking<Unit> {
        val obras = createFolder("Obras 2026")
        val inside = create("Parcela 23", at = 1_000)
        val trashed = create("Viejo", at = 2_000)
        repository.moveToFolder(inside, obras)
        repository.moveToFolder(trashed, obras)
        db.projects().setDeletedAt(trashed, deletedAt = 3_000)

        repository.deleteFolder(obras)

        repository.folders().first() shouldBe emptyList()
        rootNames() shouldContainExactly listOf("Parcela 23")
        repository.project(trashed).shouldNotBeNull().folderId shouldBe null
    }

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `S006 RF-22 un proyecto borrado sale de la lista y va a la papelera`() = runBlocking<Unit> {
        val id = create("Parcela 23", at = 1_000)

        repository.moveToTrash(id)

        rootNames() shouldBe emptyList()
        repository.trash().first().map { it.project.id } shouldContainExactly listOf(id)
    }

    @Test
    fun `S006 RF-23 la papelera muestra los dias que le quedan a cada proyecto, del ultimo borrado al primero`() = runBlocking<Unit> {
        val old = create("Viejo", at = 1_000)
        val recent = create("Reciente", at = 2_000)
        now = 10 * day
        repository.moveToTrash(old)
        now = 12 * day + day / 2
        repository.moveToTrash(recent)

        val trash = repository.trash().first()

        trash.map { it.project.name } shouldContainExactly listOf("Reciente", "Viejo")
        trash.map { it.daysLeft } shouldContainExactly listOf(30, 28)
    }

    @Test
    fun `S006 RF-24 al restaurar vuelve a su carpeta con todos sus datos`() = runBlocking<Unit> {
        val obras = createFolder("Obras 2026")
        val id = create("Parcela 23", at = 1_000)
        repository.moveToFolder(id, obras)
        repository.moveToTrash(id)

        repository.restore(id, restoredLabel = "restaurado")

        repository.projects(obras).first().map { it.id } shouldContainExactly listOf(id)
        repository.project(id).shouldNotBeNull().toDraft() shouldBe draft("Parcela 23")
        repository.trash().first() shouldBe emptyList()
    }

    @Test
    fun `S006 RF-24 si su carpeta ya no existe vuelve fuera de las carpetas`() = runBlocking<Unit> {
        val obras = createFolder("Obras 2026")
        val id = create("Parcela 23", at = 1_000)
        repository.moveToFolder(id, obras)
        repository.moveToTrash(id)
        repository.deleteFolder(obras)

        repository.restore(id, restoredLabel = "restaurado")

        rootNames() shouldContainExactly listOf("Parcela 23")
    }

    @Test
    fun `S006 RF-25 si su nombre ya esta en uso se restaura como (restaurado)`() = runBlocking<Unit> {
        val id = create("Parcela 23", at = 1_000)
        repository.moveToTrash(id)
        create("parcela 23", at = 2_000)

        repository.restore(id, restoredLabel = "restaurado")

        repository.project(id).shouldNotBeNull().name shouldBe "Parcela 23 (restaurado)"
    }

    @Test
    fun `S006 RF-26 borrar definitivamente elimina el proyecto`() = runBlocking<Unit> {
        val id = create("Parcela 23", at = 1_000)
        repository.moveToTrash(id)

        repository.deleteForever(id)

        repository.trash().first() shouldBe emptyList()
        repository.project(id) shouldBe null
    }

    @Test
    fun `S006 RF-27 al cumplir 30 dias en la papelera el proyecto se elimina al purgar y antes no`() = runBlocking<Unit> {
        val expired = create("Caducado", at = 1_000)
        val kept = create("Aun no", at = 2_000)
        val active = create("Activo", at = 3_000)
        now = 100 * day
        repository.moveToTrash(expired)
        now = 101 * day
        repository.moveToTrash(kept)

        now = 130 * day
        repository.purgeTrash() shouldBe 1

        repository.project(expired) shouldBe null
        repository.trash().first().map { it.project.id } shouldContainExactly listOf(kept)
        repository.project(active).shouldNotBeNull()
    }
}

package es.topophone.data.grids

import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Las rejillas instaladas: estado, uso por proyecto, borrado y lectura de geoides y rejillas (S034/RF-12, RF-17, RF-23, RF-24). */
class GridStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
    private val bora = "fr_ign_ggpf02-Bora.tif"

    private fun definition(gridSteps: String) = CrsDefinition(
        epsg = 2154, name = "n", projected = true, datumCode = 1, datumName = "d", ellipsoidSemiMajorM = 6378137.0,
        ellipsoidInverseFlattening = 298.257222101, primeMeridianDeg = 0.0, method = "lcc2", parameters = emptyMap(), unitMetres = 1.0,
        area = null, frame = "ETRS89", transformationName = "t", transformationAccuracyM = null, transformationArea = null,
        transformationSteps = gridSteps,
    )

    private fun project(name: String, crs: ProjectCrs) = Project(
        id = name, name = name, author = "", description = "", crs = crs, linearUnit = LinearUnit.METRE, angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89, folderId = null, createdAtMs = 0, modifiedAtMs = 0, openedAtMs = null,
        deletedAtMs = null,
    )

    private fun store(projects: List<Project> = emptyList()): GridStore {
        val directory = folder.newFolder()
        File(root, "fixtures/geodesia/rejillas/$bora").copyTo(File(directory, bora))
        return GridStore(directory) { projects }
    }

    @Test
    fun `S034 RF-17 las rejillas que trae la app constan como incluidas y las demas como no instaladas`() {
        val store = store()

        store.state("es_ign_SPED2ETV2.tif") shouldBe GridState.Bundled
        store.state("es_ign_egm08-rednap.tif") shouldBe GridState.Bundled
        store.state("us_nga_egm08_25.tif") shouldBe GridState.Missing
        store.state(bora) shouldBe GridState.Installed(File(root, "fixtures/geodesia/rejillas/$bora").length())
    }

    @Test
    fun `S034 RF-23 la lista de instaladas da el tamano y los proyectos que usan cada una`() {
        val user = project("Obra", LibraryCrs(definition("null"), CorrectionsDatum.ETRS89, bora))
        val other = project("Otra", LibraryCrs(definition("G;grid=otra.tif;inv=0")))
        val store = store(listOf(user, other, project("Espana", ProjectCrs.ETRS89_UTM30)))

        val installed = runBlocking { store.installedGrids() }

        installed.map { it.entry.name } shouldBe listOf(bora)
        installed.single().sizeBytes shouldBe File(root, "fixtures/geodesia/rejillas/$bora").length()
        installed.single().usedBy shouldBe listOf("Obra")
        installed.single().inUse shouldBe true
    }

    @Test
    fun `S034 RF-23 una rejilla que un proyecto usa no se borra y una que nadie usa si, y sube la generacion`() {
        val user = project("Obra", LibraryCrs(definition("null"), CorrectionsDatum.ETRS89, bora))
        val inUse = store(listOf(user))
        val free = store(emptyList())

        runBlocking { inUse.delete(bora) } shouldBe DeleteResult.InUse(listOf("Obra"))
        inUse.state(bora) shouldBe GridState.Installed(inUse.fileOf(bora)!!.length())

        val before = free.generation
        runBlocking { free.delete(bora) } shouldBe DeleteResult.Deleted
        free.state(bora) shouldBe GridState.Missing
        (free.generation > before) shouldBe true
        runBlocking { free.delete(bora) } shouldBe DeleteResult.NotInstalled
    }

    @Test
    fun `S034 RF-22 un fichero a medias no cuenta como rejilla instalada ni se lista`() {
        val store = store()
        store.partFileOf("us_nga_egm08_25.tif")!!.writeBytes(ByteArray(10))

        store.state("us_nga_egm08_25.tif") shouldBe GridState.Missing
        store.grids.read("us_nga_egm08_25.tif").shouldBeNull()
        runBlocking { store.installedGrids() }.map { it.entry.name } shouldBe listOf(bora)
    }

    @Test
    fun `S034 RF-24 un nombre que intenta salir de la carpeta no se lee`() {
        val store = store()

        store.fileOf("../secreto").shouldBeNull()
        store.fileOf("a/b.tif").shouldBeNull()
        store.grids.read("../x").shouldBeNull()
    }

    @Test
    fun `S034 RF-12 las rejillas de la app se leen del APK y las instaladas de la carpeta`() {
        val store = store()

        store.grids.read("es_ign_SPED2ETV2.tif").shouldNotBeNull()
        store.grids.read(bora).shouldNotBeNull()
        store.grids.read("no_existe.tif").shouldBeNull()
    }

    @Test
    fun `S034 RF-16 un geoide instalado da la ondulacion de PROJ y uno que falta no da nada`() {
        val store = store()
        val row = File(root, "fixtures/geodesia/geoide_cdn.csv").readLines().first {
            it.startsWith("$bora,") &&
                it.split(',')[3].isNotEmpty()
        }
            .split(',')

        val geoid = store.geoid(bora).shouldNotBeNull()

        geoid.undulationM(row[1].toDouble(), row[2].toDouble()).shouldNotBeNull() shouldBe row[3].toDouble().plusOrMinus(0.001)
        store.geoid("us_nga_egm08_25.tif").shouldBeNull()
        store.geoid("es_ign_egm08-rednap.tif").shouldNotBeNull() // el que trae la app
    }
}

package es.topophone.core.model.codes

import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.ProjectDefaults
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los tipos de las bibliotecas de códigos y su paleta de colores (S023/RF-5, RF-8, RF-9). */
class CodeModelTest {

    @Test
    fun `S023 RF-5 un codigo lleva codigo, descripcion, geometria, color y atributos`() {
        val code = SurveyCode(
            id = "c1",
            code = "ARQ",
            description = "Arqueta",
            geometry = CodeGeometry.POINT,
            color = 0x1E88E5,
            attributes = listOf(CodeAttribute("Material", AttributeType.LIST, "PVC", listOf("PVC", "Hormigón"))),
        )

        code.geometry shouldBe CodeGeometry.POINT
        code.attributes.single().options shouldBe listOf("PVC", "Hormigón")
        CodeGeometry.entries.map { it.name } shouldBe listOf("POINT", "LINE", "POLYGON")
        AttributeType.entries.map { it.name } shouldBe listOf("TEXT", "INTEGER", "NUMBER", "LIST")
    }

    @Test
    fun `S023 RF-8 la paleta ofrece 12 colores distintos`() {
        CodePalette.COLORS.size shouldBe 12
        CodePalette.COLORS.toSet().size shouldBe 12
    }

    @Test
    fun `S023 RF-8 sin color elegido cada geometria tiene el suyo, de la paleta y distinto`() {
        val defaults = CodeGeometry.entries.map { CodePalette.defaultFor(it) }

        defaults.toSet().size shouldBe 3
        CodePalette.COLORS shouldContainAll defaults
    }

    @Test
    fun `S023 RF-8 el color se escribe como #RRGGBB en mayusculas y se lee sin distinguirlas`() {
        CodePalette.format(0x1E88E5) shouldBe "#1E88E5"
        CodePalette.format(0x00000F) shouldBe "#00000F"
        CodePalette.parse("#1e88e5") shouldBe 0x1E88E5
        CodePalette.parse(" #1E88E5 ") shouldBe 0x1E88E5
    }

    @Test
    fun `S023 RF-8 un color que no es #RRGGBB no se lee`() {
        listOf("", "red", "1E88E5", "#1E88E", "#1E88E55", "#GGGGGG", "#-12345").forEach {
            CodePalette.parse(it) shouldBe null
        }
    }

    @Test
    fun `S023 RF-19 la eleccion de biblioteca de un proyecto es mantener, ninguna o una biblioteca`() {
        val choices = listOf(LibraryChoice.Keep, LibraryChoice.None, LibraryChoice.Library("default:general"))

        (choices[2] as LibraryChoice.Library).id shouldBe "default:general"
        choices.toSet().size shouldBe 3
    }

    @Test
    fun `S023 RF-19 un proyecto nuevo propone la biblioteca de Ajustes, o mantiene (ninguna) si no hay`() {
        ProjectDefaults().toDraft().libraryChoice shouldBe LibraryChoice.Keep
        ProjectDefaults(codeLibraryId = "L1").toDraft().libraryChoice shouldBe LibraryChoice.Library("L1")
        ProjectDefaults(codeLibraryId = "L1").withCrs(ProjectCrs.ETRS89_UTM30).codeLibraryId shouldBe "L1"
    }

    @Test
    fun `S023 RF-21 editar un proyecto mantiene su copia salvo que se cambie`() {
        val project = ProjectDefaults().toDraft().let {
            Project("p", "N", "", "", it.crs, it.linearUnit, it.angularUnit, it.correctionsDatum, null, 0, 0, null, null)
        }

        project.toDraft().libraryChoice shouldBe LibraryChoice.Keep
    }
}

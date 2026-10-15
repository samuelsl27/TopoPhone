package es.topophone.core.model.project

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Un proyecto guardado y los datos de su formulario (S006/RF-14). */
class ProjectTest {

    @Test
    fun `S006 RF-14 al editar un proyecto el formulario parte de sus datos guardados`() {
        val project = Project(
            id = "id-1",
            name = "Calle Mayor 12",
            author = "S. S.",
            description = "Fachada",
            crs = ProjectCrs.ETRS89_UTM31,
            linearUnit = LinearUnit.INTERNATIONAL_FOOT,
            angularUnit = AngularUnit.GON,
            correctionsDatum = CorrectionsDatum.REGCAN95,
            folderId = "carpeta-1",
            createdAtMs = 1,
            modifiedAtMs = 2,
            openedAtMs = 3,
            deletedAtMs = null,
        )

        project.toDraft() shouldBe ProjectDraft(
            name = "Calle Mayor 12",
            author = "S. S.",
            description = "Fachada",
            crs = ProjectCrs.ETRS89_UTM31,
            linearUnit = LinearUnit.INTERNATIONAL_FOOT,
            angularUnit = AngularUnit.GON,
            correctionsDatum = CorrectionsDatum.REGCAN95,
        )
        ProjectFolder("carpeta-1", "Obras 2026").name shouldBe "Obras 2026"
    }

    @Test
    fun `S006 RF-6 el formulario de proyecto nuevo parte de los valores por defecto, sin nombre`() {
        ProjectDefaults().toDraft() shouldBe ProjectDraft(
            name = "",
            author = "",
            description = "",
            crs = ProjectCrs.ETRS89_UTM30,
            linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS,
            correctionsDatum = CorrectionsDatum.ETRS89,
        )
    }
}

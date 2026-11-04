package es.topophone.core.model.codes

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Los códigos de línea y de polígono en el selector del recolector y en el cambio de código de una línea (S025/RF-1, RF-20). */
class ProjectCodesGeometryTest {

    private fun code(name: String, geometry: CodeGeometry) = SurveyCode(name, name, "", geometry, 0)

    private val library = listOf(code("ARQ", CodeGeometry.POINT), code("BORD", CodeGeometry.LINE), code("PARC", CodeGeometry.POLYGON))
    private val own = listOf(code("POZ", CodeGeometry.POINT), code("TAPIA", CodeGeometry.LINE))

    @Test
    fun `S025 RF-1 por defecto solo se ofrecen los codigos de punto como en S024`() {
        ProjectCodes.available(library, own).map { it.code } shouldBe listOf("ARQ", "POZ")
    }

    @Test
    fun `S025 RF-1 el recolector con lineas ofrece punto, linea y poligono por orden alfabetico`() {
        ProjectCodes.available(library, own, ProjectCodes.ALL_GEOMETRIES).map { it.code } shouldBe
            listOf("ARQ", "BORD", "PARC", "POZ", "TAPIA")
    }

    @Test
    fun `S025 RF-20 cambiar el codigo de una linea ofrece solo linea y poligono`() {
        ProjectCodes.available(library, own, ProjectCodes.LINE_GEOMETRIES).map { it.code } shouldBe listOf("BORD", "PARC", "TAPIA")
    }
}

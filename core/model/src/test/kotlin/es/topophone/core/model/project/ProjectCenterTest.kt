package es.topophone.core.model.project

import es.topophone.core.model.map.MapViewport
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El centro del proyecto y su punto central (S006/RF-32, RF-35, RF-37). */
class ProjectCenterTest {
    private val central = CentralPoint(39.4699123456789, -0.3763123456789)
    private val points = listOf(CentralPoint(40.0, -1.0), CentralPoint(42.0, -3.0))
    private val viewport = MapViewport(41.0, -2.0, 15.0)

    @Test
    fun `S006 RF-37 el punto central manda sobre los puntos y el encuadre`() {
        ProjectCenter.of(central, points, viewport) shouldBe central
    }

    @Test
    fun `S006 RF-37 sin punto central el centro es la media de los puntos`() {
        val center = ProjectCenter.of(null, points, viewport)!!

        center.latitudeDeg shouldBe (41.0 plusOrMinus 1e-12)
        center.longitudeDeg shouldBe (-2.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S006 RF-37 sin punto central ni puntos el centro es el del encuadre`() {
        ProjectCenter.of(null, emptyList(), MapViewport(38.5, -1.5, 12.0)) shouldBe CentralPoint(38.5, -1.5)
    }

    @Test
    fun `S006 RF-37 sin punto central, puntos ni encuadre no hay centro`() {
        ProjectCenter.of(null, emptyList(), null).shouldBeNull()
    }

    @Test
    fun `S006 RF-35 al borrar el punto central se vuelve a calcular sin el`() {
        val project = Project(
            id = "p", name = "n", author = "", description = "", crs = ProjectCrs.ETRS89_UTM30, linearUnit = LinearUnit.METRE,
            angularUnit = AngularUnit.DMS, correctionsDatum = CorrectionsDatum.ETRS89, folderId = null,
            createdAtMs = 0, modifiedAtMs = 0, openedAtMs = null, deletedAtMs = null, centralPoint = central,
        )

        ProjectCenter.of(project.centralPoint, points, viewport) shouldBe central
        ProjectCenter.of(project.copy(centralPoint = null).centralPoint, points, viewport)!!.latitudeDeg shouldBe (41.0 plusOrMinus 1e-12)
    }

    @Test
    fun `S006 RF-32 el punto central es opcional y no se redondea al pasar por el formulario`() {
        val draft = ProjectDraft("n", "", "", ProjectCrs.ETRS89_UTM30, LinearUnit.METRE, AngularUnit.DMS, CorrectionsDatum.ETRS89)

        draft.centralPoint.shouldBeNull()
        draft.copy(centralPoint = central).withCrs(ProjectCrs.ETRS89_UTM31).centralPoint shouldBe central
    }
}

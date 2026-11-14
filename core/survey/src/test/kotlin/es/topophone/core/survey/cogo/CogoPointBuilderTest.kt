package es.topophone.core.survey.cogo

import es.topophone.core.model.cogo.CogoEntry
import es.topophone.core.model.cogo.CogoOperation
import es.topophone.core.model.cogo.CogoRef
import es.topophone.core.model.cogo.CogoValue
import es.topophone.core.model.cogo.IntersectionSide
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectDefaults
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El punto del proyecto que se guarda desde un resultado de COGO (S038/RF-18, RF-21, RF-22, RF-24). */
class CogoPointBuilderTest {
    private val project = Project(
        id = "p1",
        name = "Obra",
        author = "",
        description = "",
        crs = ProjectDefaults().crs,
        linearUnit = ProjectDefaults().linearUnit,
        angularUnit = ProjectDefaults().angularUnit,
        correctionsDatum = ProjectDefaults().correctionsDatum,
        folderId = null,
        createdAtMs = 0L,
        modifiedAtMs = 0L,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private val ref = CogoRef(
        operation = CogoOperation.INTERSECTION_DISTANCE_DISTANCE,
        entries = listOf(
            CogoEntry("first", "A", 440_000.0, 4_474_000.0, 650.0),
            CogoEntry("second", null, 440_100.25, 4_474_000.0, null),
        ),
        values = listOf(CogoValue("distance1", 60.0), CogoValue("distance2", 70.0)),
        solution = IntersectionSide.LEFT,
    )

    private fun build(
        easting: Double = 440_036.123456789012,
        northing: Double = 4_474_048.987654321098,
        height: Double? = 651.5,
        project: Project = this.project,
    ) = CogoPointBuilder().build("id-1", project, "7", easting, northing, height, ref, 5_000L)

    private fun built(
        easting: Double = 440_036.123456789012,
        northing: Double = 4_474_048.987654321098,
        height: Double? = 651.5,
        project: Project = this.project,
    ) = build(easting, northing, height, project).shouldBeInstanceOf<CogoPointResult.Built>().point

    @Test
    fun `S038 RF-24 el punto lleva el origen COGO, sin medida, jalon ni calidad, y el rastro del calculo`() {
        val point = built()

        point.origin shouldBe PointOrigin.COGO
        point.id shouldBe "id-1"
        point.projectId shouldBe "p1"
        point.name shouldBe "7"
        point.description shouldBe ""
        point.measurement.shouldBeNull()
        point.copyOf.shouldBeNull()
        point.edited shouldBe false
        point.hidden shouldBe false
        point.createdAtMs shouldBe 5_000L
        point.cogo shouldBe ref // RF-22: la operacion, las entradas por valor y lo escrito
    }

    @Test
    fun `S038 RF-22 el Este y el Norte se guardan tal cual, sin redondear y sin ida y vuelta`() {
        val point = built()

        point.coordinates.easting shouldBe 440_036.123456789012
        point.coordinates.northing shouldBe 4_474_048.987654321098
        val typed = point.typed!!
        typed.form shouldBe EntryForm.PROJECT_CRS
        typed.first shouldBe 440_036.123456789012
        typed.second shouldBe 4_474_048.987654321098
        typed.heightKind shouldBe HeightKind.PROJECT
    }

    @Test
    fun `S038 RF-18 con altura el punto la tiene en el sistema del proyecto y sin ella no tiene altura`() {
        val with = built(height = 651.5)
        val without = built(height = null)

        with.hasHeight shouldBe true
        with.coordinates.height!! shouldBe (651.5 plusOrMinus 1e-9)
        without.hasHeight shouldBe false
        without.coordinates.height.shouldBeNull()
        without.coordinates.ground.ellipsoidalHeightM.shouldBeNull()
    }

    @Test
    fun `S038 RF-18 con altura elipsoidal en el proyecto la altura escrita es la elipsoidal`() {
        val ellipsoidal = project.copy(heightSystem = HeightSystem.ELLIPSOIDAL)

        val point = built(height = 700.25, project = ellipsoidal)

        point.coordinates.ground.ellipsoidalHeightM!! shouldBe (700.25 plusOrMinus 1e-9)
        point.coordinates.height!! shouldBe (700.25 plusOrMinus 1e-9)
    }

    @Test
    fun `S038 RF-21 un resultado fuera del ambito del sistema del proyecto no se construye`() {
        build(easting = 0.0, northing = 0.0).shouldBeInstanceOf<CogoPointResult.Rejected>()
        build(easting = 440_000.0, northing = 9_000_000.0).shouldBeInstanceOf<CogoPointResult.Rejected>()
    }
}

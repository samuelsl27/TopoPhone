package es.topophone.core.survey

import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectDefaults
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El punto que se guarda desde un PK y un desplazamiento (S049/RF-22, plan A7). */
class AxisPointBuilderTest {
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
    private val geometry = AlignmentGeometry(AlignmentFixtures.alignment("Eje A"))

    private fun built(pk: Double, offset: Double, elevation: Double?) =
        AxisPointBuilder().build("id-1", project, "10", "Eje A", geometry.pointAt(pk, offset)!!, elevation, 5_000L)
            .shouldBeInstanceOf<AxisPointResult.Built>().point

    @Test
    fun `S049 RF-22 el punto lleva el origen Calculado sobre eje con su PK y su desplazamiento`() {
        val point = built(200.0, -3.5, 101.25)

        point.origin shouldBe PointOrigin.ON_ALIGNMENT
        point.onAxis.shouldNotBeNull().alignmentName shouldBe "Eje A"
        point.onAxis!!.chainageM shouldBe 200.0
        point.onAxis!!.offsetM shouldBe -3.5
        point.name shouldBe "10"
        point.measurement.shouldBeNull()
        point.createdAtMs shouldBe 5_000L
    }

    @Test
    fun `S049 RF-22 el Este y el Norte del punto son los del eje sin redondear`() {
        val axis = geometry.pointAt(200.0, 3.5)!!

        val point = built(200.0, 3.5, null)

        point.coordinates.easting shouldBe axis.easting
        point.coordinates.northing shouldBe axis.northing
        point.typed.shouldNotBeNull().first shouldBe axis.easting
    }

    @Test
    fun `S049 RF-22 con rasante el punto tiene la cota y sin ella no tiene altura`() {
        built(200.0, 0.0, 101.25).coordinates.height shouldBe (101.25 plusOrMinus 1e-12)
        built(200.0, 0.0, 101.25).hasHeight shouldBe true
        built(200.0, 0.0, null).coordinates.height.shouldBeNull()
        built(200.0, 0.0, null).hasHeight shouldBe false
    }

    @Test
    fun `S049 RF-22 un punto fuera del ambito del sistema no se construye`() {
        val far = AxisPoint(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        AxisPointBuilder().build("id", project, "1", "Eje", far, null, 1L).shouldBeInstanceOf<AxisPointResult.Rejected>()
    }

    @Test
    fun `S050 RF-15 un punto de una seccion lleva su codigo de seccion y los demas no`() {
        val point = AxisPointBuilder().build(
            "id-2",
            project,
            "11",
            "Eje A",
            geometry.pointAt(100.0, 3.5)!!,
            101.0,
            5_000L,
            sectionCode = "BD",
        )
            .shouldBeInstanceOf<AxisPointResult.Built>().point

        point.origin shouldBe PointOrigin.ON_ALIGNMENT
        point.onAxis.shouldNotBeNull().sectionCode shouldBe "BD"
        built(100.0, 3.5, 101.0).onAxis.shouldNotBeNull().sectionCode.shouldBeNull()
    }
}

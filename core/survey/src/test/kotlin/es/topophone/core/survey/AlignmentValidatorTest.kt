package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalVertex
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Los huecos entre tramos (S049/RF-5) y las rasantes que no valen (S049/RF-10). */
class AlignmentValidatorTest {

    @Test
    fun `S049 RF-5 los ejes de ejemplo se encadenan`() {
        for (axis in AlignmentFixtures.axes) {
            AlignmentValidator.validate(AlignmentFixtures.alignment(axis).elements).shouldBeNull()
        }
    }

    @Test
    fun `S049 RF-5 un hueco de 5 cm entre dos tramos da el numero del segundo`() {
        val elements = AlignmentFixtures.alignment("Eje A").elements.toMutableList()
        val second = elements[1]
        elements[1] = second.copy(startNorthing = second.startNorthing + 0.05)

        val issue = AlignmentValidator.validate(elements).shouldBeInstanceOf<AlignmentIssue.Gap>()

        issue.element shouldBe 2
        issue.gapM shouldBe (0.05 plusOrMinus 1e-6)
    }

    @Test
    fun `S049 RF-5 un hueco de menos de 1 cm se admite`() {
        val elements = AlignmentFixtures.alignment("Eje A").elements.toMutableList()
        val last: AlignmentElement = elements[4]
        elements[4] = last.copy(startEasting = last.startEasting + 0.009)

        AlignmentValidator.validate(elements).shouldBeNull()
    }

    private fun v(pk: Double, z: Double, curve: VerticalCurve = VerticalCurve.None) = VerticalVertex(pk, z, curve)

    @Test
    fun `S049 RF-10 las rasantes de ejemplo valen en su eje`() {
        for (axis in listOf("Eje A", "Eje B")) {
            val a = AlignmentFixtures.alignment(axis)
            ProfileValidator.validate(a.profile!!.vertices, a.startChainage, a.endChainage).shouldBeNull()
        }
    }

    @Test
    fun `S049 RF-10 dos vertices con el mismo PK dan el vertice y el motivo`() {
        val issue = ProfileValidator.validate(listOf(v(0.0, 10.0), v(100.0, 11.0), v(100.0, 12.0), v(200.0, 13.0)), 0.0, 200.0)

        issue shouldBe ProfileIssue.RepeatedChainage(vertex = 3, chainage = 100.0)
    }

    @Test
    fun `S049 RF-10 un vertice fuera del PK del eje se rechaza`() {
        ProfileValidator.validate(listOf(v(0.0, 10.0), v(100.0, 11.0), v(250.0, 12.0)), 0.0, 200.0) shouldBe
            ProfileIssue.OutsideAxis(vertex = 3, chainage = 250.0)
        ProfileValidator.validate(listOf(v(-5.0, 10.0), v(100.0, 11.0)), 0.0, 200.0) shouldBe
            ProfileIssue.OutsideAxis(vertex = 1, chainage = -5.0)
    }

    @Test
    fun `S049 RF-10 un acuerdo mas largo que la distancia al vertice vecino no cabe`() {
        val vertices = listOf(v(0.0, 10.0), v(100.0, 12.0, VerticalCurve.Parabola(36.0)), v(110.0, 11.0), v(200.0, 13.0))

        ProfileValidator.validate(vertices, 0.0, 200.0) shouldBe ProfileIssue.CurveDoesNotFit(vertex = 2, chainage = 100.0)
    }

    @Test
    fun `S049 RF-10 dos acuerdos contiguos que se solapan no caben`() {
        val vertices = listOf(
            v(0.0, 10.0),
            v(100.0, 12.0, VerticalCurve.Parabola(100.0)),
            v(160.0, 11.0, VerticalCurve.Parabola(100.0)),
            v(300.0, 13.0),
        )

        ProfileValidator.validate(vertices, 0.0, 300.0).shouldBeInstanceOf<ProfileIssue.CurveDoesNotFit>().vertex shouldBe 2
    }

    @Test
    fun `S049 RF-10 un acuerdo en un extremo o sin longitud no cabe y uno justo en el limite si`() {
        ProfileValidator.validate(listOf(v(0.0, 10.0, VerticalCurve.Parabola(10.0)), v(100.0, 12.0)), 0.0, 100.0) shouldBe
            ProfileIssue.CurveDoesNotFit(vertex = 1, chainage = 0.0)
        ProfileValidator.validate(listOf(v(0.0, 10.0), v(50.0, 12.0, VerticalCurve.Parabola(0.0)), v(100.0, 11.0)), 0.0, 100.0)
            .shouldBeInstanceOf<ProfileIssue.CurveDoesNotFit>()
        ProfileValidator.validate(
            listOf(v(0.0, 10.0), v(50.0, 12.0, VerticalCurve.Parabola(100.0)), v(100.0, 11.0)),
            0.0,
            100.0,
        ).shouldBeNull()
    }

    @Test
    fun `S049 RF-10 un acuerdo circular se mide por sus tangentes y no por su arco`() {
        // Pendientes de 10 % y -10 %: Δα ≈ 0,199 rad y T ≈ R·tan(Δα/2) ≈ 0,1·R; con R = 600 caben 60 m a cada lado del PK 100.
        val fits = listOf(v(0.0, 0.0), v(100.0, 10.0, VerticalCurve.Circular(600.0, 120.0)), v(200.0, 0.0))
        val tooBig = listOf(v(0.0, 0.0), v(100.0, 10.0, VerticalCurve.Circular(1100.0, 220.0)), v(200.0, 0.0))

        ProfileValidator.validate(fits, 0.0, 200.0).shouldBeNull()
        ProfileValidator.validate(tooBig, 0.0, 200.0).shouldBeInstanceOf<ProfileIssue.CurveDoesNotFit>()
    }
}

package es.topophone.core.survey

import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import org.junit.jupiter.api.Test

/** Del eje del fichero al del proyecto: unidad, ámbito y otro sistema frente a pyproj (S049/RF-5, RF-10 a RF-12, decisión D2). */
class AlignmentProjectorTest {
    private val project = ProjectCrs.ETRS89_UTM30

    private fun draft(axis: String, withProfile: Boolean = true) = AlignmentFixtures.alignment(axis, withProfile).let {
        AlignmentDraft(it.elements, AlignmentFixtures.endsOf(axis), it.profile?.vertices)
    }

    private fun converted(projection: AlignmentProjection) = projection.shouldBeInstanceOf<AlignmentProjection.Converted>()

    @Test
    fun `S049 RF-11 en el sistema del proyecto y en metros el eje queda tal cual`() {
        val result = converted(AlignmentProjector(project).convert(draft("Eje A")))

        result.elements shouldBe AlignmentFixtures.alignment("Eje A").elements
        result.approximated shouldBe false
        result.profile.shouldNotBeNull().size shouldBe 4
        result.profileOmission.shouldBeNull()
        result.extent.minEasting shouldBe (440000.0 plusOrMinus 1e-9)
        (result.extent.maxEasting > 440300.0) shouldBe true
    }

    @Test
    fun `S049 RF-11 en pies cada longitud se pasa a metros y el eje coincide con el de metros`() {
        val feet = LinearUnit.INTERNATIONAL_FOOT.metres
        val original = AlignmentFixtures.alignment("Eje B")
        val inFeet = AlignmentDraft(
            original.elements.map {
                it.copy(
                    startChainage = it.startChainage / feet,
                    length = it.length / feet,
                    startEasting = it.startEasting / feet,
                    startNorthing = it.startNorthing / feet,
                    startCurvature = it.startCurvature * feet,
                    endCurvature = it.endCurvature * feet,
                )
            },
            profile = original.profile!!.vertices.map {
                val curve = when (val c = it.curve) {
                    is VerticalCurve.Parabola -> VerticalCurve.Parabola(c.length / feet)
                    else -> c
                }
                VerticalVertex(it.chainage / feet, it.elevation / feet, curve)
            },
        )

        val result = converted(AlignmentProjector(project, unit = LinearUnit.INTERNATIONAL_FOOT).convert(inFeet))

        for ((a, b) in result.elements.zip(original.elements)) {
            a.startEasting shouldBe (b.startEasting plusOrMinus 1e-6)
            a.length shouldBe (b.length plusOrMinus 1e-9)
            a.startChainage shouldBe (b.startChainage plusOrMinus 1e-9)
            a.endCurvature shouldBe (b.endCurvature plusOrMinus 1e-12)
        }
        result.profile.shouldNotBeNull()[1].elevation shouldBe (52.0 plusOrMinus 1e-9)
        (result.profile[1].curve as VerticalCurve.Parabola).length shouldBe
            (original.profile!!.vertices[1].curve.let { (it as VerticalCurve.Parabola).length } plusOrMinus 1e-9)
    }

    @Test
    fun `S049 RF-12 un eje fuera del ambito del sistema del proyecto no se importa`() {
        val far = AlignmentDraft(listOf(AlignmentElement.line(0.0, 100.0, 0.0, 0.0, 0.0)))

        AlignmentProjector(project).convert(far).shouldBeInstanceOf<AlignmentProjection.Rejected>().issue shouldBe
            AlignmentIssue.OutsideCrsArea
    }

    @Test
    fun `S049 RF-12 un tramo del medio que sale del ambito tambien rechaza el eje`() {
        // Una recta de 5.000 km hacia el Este pasa por el ámbito y sale de él entre sus extremos de muestreo.
        val long = AlignmentDraft(listOf(AlignmentElement.line(0.0, 5_000_000.0, 440000.0, 4474000.0, Math.PI / 2)))

        AlignmentProjector(project).convert(long).shouldBeInstanceOf<AlignmentProjection.Rejected>().issue shouldBe
            AlignmentIssue.OutsideCrsArea
    }

    @Test
    fun `S049 RF-5 un hueco entre tramos o un final declarado distinto rechaza el eje con el tramo`() {
        val gap = AlignmentFixtures.alignment("Eje A").elements.toMutableList()
        gap[1] = gap[1].copy(startNorthing = gap[1].startNorthing + 0.05)

        AlignmentProjector(project).convert(AlignmentDraft(gap)).shouldBeInstanceOf<AlignmentProjection.Rejected>()
            .issue.shouldBeInstanceOf<AlignmentIssue.Gap>().element shouldBe 2
    }

    @Test
    fun `S049 RF-10 una rasante que no vale se omite con su vertice y el eje se importa`() {
        val d = draft("Eje C", withProfile = false)
        val bad = AlignmentDraft(
            d.elements,
            profile = listOf(
                VerticalVertex(0.0, 10.0),
                VerticalVertex(100.0, 11.0),
                VerticalVertex(100.0, 12.0),
                VerticalVertex(200.0, 13.0),
            ),
        )

        val result = converted(AlignmentProjector(project).convert(bad))

        result.profile.shouldBeNull()
        result.profileOmission shouldBe ProfileOmission.Invalid(ProfileIssue.RepeatedChainage(3, 100.0))
        result.elements.size shouldBe 1
    }

    @Test
    fun `S049 D3 la omision que traia el fichero se conserva`() {
        val d = AlignmentDraft(draft("Eje C", false).elements, profileOmission = ProfileOmission.MultipleProfiles)

        converted(AlignmentProjector(project).convert(d)).profileOmission shouldBe ProfileOmission.MultipleProfiles
    }

    @Test
    fun `S049 RF-11 un proyecto en geograficas no admite ejes`() {
        AlignmentProjector(ProjectCrs.ETRS89_GEOGRAPHIC).convert(draft("Eje C")).shouldBeInstanceOf<AlignmentProjection.Rejected>()
            .issue shouldBe AlignmentIssue.GeographicProject
    }

    @Test
    fun `S049 RF-11 un eje en ETRS89 UTM 31 queda sobre el de pyproj a menos de 1 mm`() {
        val rows = SurveyFixtures.rows("esperado-crs.csv", "ejes")
        val source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)

        val result = converted(AlignmentProjector(project, source).convert(draft("Eje A")))

        result.approximated shouldBe true
        result.elements.all { it.kind == ElementKind.LINE } shouldBe true
        val geometry = AlignmentGeometry(result.elements)
        for (row in rows) {
            val located = geometry.locate(row[4].toDouble(), row[5].toDouble()).shouldNotBeNull()
            located.distance shouldBe (0.0 plusOrMinus 1e-3)
            val ratio = (located.chainage - geometry.startChainage) / (row[1].toDouble() - 0.0).coerceAtLeast(1e-9)
            if (row[1].toDouble() > 1.0) (abs(ratio - 1.0) < 0.01) shouldBe true
        }
    }

    @Test
    fun `S049 RF-11 al cambiar de sistema los vertices de la rasante siguen en el mismo punto del eje`() {
        val source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 31)

        val result = converted(AlignmentProjector(project, source).convert(draft("Eje A")))

        val profile = result.profile.shouldNotBeNull()
        profile.size shouldBe 4
        profile.last().chainage shouldBe (result.elements.last().endChainage plusOrMinus 1e-6)
        profile.first().chainage shouldBe (0.0 plusOrMinus 1e-9)
        // La parábola original medía 100 m; en el plano del proyecto se escala con el eje (~0,2 %).
        val parabola = profile[1].curve as VerticalCurve.Parabola
        (abs(parabola.length - 100.0) < 1.0) shouldBe true
        // El vértice del PK 150 queda al 150/420 del eje, a menos del 0,5 % de la longitud total.
        (abs((profile[1].chainage / profile.last().chainage) - 150.0 / 420.0) < 0.005) shouldBe true
    }
}

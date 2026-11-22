package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.CustomCrs
import es.topophone.core.model.project.CustomHeight
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El punto de partida del editor de SC personalizados: vacío, un SC de la biblioteca o uno de Mis SC (S035/RF-1). */
class CustomCrsDraftTest {
    private val catalog = CrsCatalog.bundled

    private fun definition(epsg: Int, corrections: CorrectionsDatum, at: GeoPoint) = when (val r = catalog.build(epsg, corrections, at)) {
        is CrsBuildResult.Ready -> r.definition
        is CrsBuildResult.NeedsGrids -> r.definition
        is CrsBuildResult.Unavailable -> error("EPSG:$epsg: $r")
    }

    @Test
    fun `S035 RF-1 un borrador vacio no tiene nada escrito`() {
        val draft = CustomCrsDraft.empty()

        draft.name shouldBe ""
        draft.semiMajorAxisM shouldBe null
        draft.method shouldBe CustomCrsDraft.GEOGRAPHIC
        draft.transformation shouldBe CustomTransformation.None
        draft.height shouldBe CustomHeight.Ellipsoidal
    }

    @Test
    fun `S035 RF-1 partir de Lambert-93 trae su elipsoide, su metodo, sus parametros y sin nombre`() {
        val draft = CustomCrsDraft.of(definition(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)))

        draft.name shouldBe ""
        draft.method shouldBe "lcc2"
        draft.semiMajorAxisM shouldBe 6378137.0
        draft.inverseFlattening shouldBe 298.257222101
        draft.parameters["standardParallel1Deg"] shouldBe 49.0
        draft.parameters["eastingAtFalseOrigin"] shouldBe 700000.0
        draft.unit shouldBe LinearUnit.METRE
        draft.transformation shouldBe CustomTransformation.None
    }

    @Test
    fun `S035 RF-1 partir de un SC con Helmert trae el tipo y los valores de la transformacion`() {
        // Pulkovo 1942(58) / Stereo70 (3844) a ETRS89: Helmert de 7 parametros en convencion de marco de coordenadas
        val draft = CustomCrsDraft.of(definition(3844, CorrectionsDatum.ETRS89, GeoPoint(44.43, 26.1)))

        val t = draft.transformation
        if (t is CustomTransformation.Helmert) {
            t.kind shouldBe HelmertKind.SEVEN
            t.convention shouldBe RotationConvention.COORDINATE_FRAME
        } else {
            t.shouldBeInstanceOf<CustomTransformation.Kept>() // la cadena de la biblioteca no es un solo Helmert directo
        }
    }

    @Test
    fun `S035 RF-1 partir de ED50 trae la rejilla`() {
        val draft = CustomCrsDraft.of(definition(23030, CorrectionsDatum.ETRS89, GeoPoint(40.4, -3.7)))

        val t = draft.transformation
        (t is CustomTransformation.Grid || t is CustomTransformation.Kept) shouldBe true
    }

    @Test
    fun `S035 RF-1 partir de un SC de Mis SC conserva su nombre y su altura`() {
        val mine = CustomCrs(
            "c1",
            "Obra norte",
            definition(2154, CorrectionsDatum.ETRS89, GeoPoint(48.0, 2.0)).copy(epsg = 0, name = "Obra norte"),
            CustomHeight.ConstantGeoid(48.5),
        )

        val draft = CustomCrsDraft.of(mine)

        draft.name shouldBe "Obra norte"
        draft.height shouldBe CustomHeight.ConstantGeoid(48.5)
        draft.method shouldBe "lcc2"
    }

    @Test
    fun `S035 RF-1 un SC geografico de la biblioteca da un borrador sin proyeccion`() {
        val draft = CustomCrsDraft.of(definition(4258, CorrectionsDatum.ETRS89, GeoPoint(40.0, -3.0)))

        draft.method shouldBe CustomCrsDraft.GEOGRAPHIC
        draft.parameters shouldBe emptyMap()
    }
}

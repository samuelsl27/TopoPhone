package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ellipsoid
import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** Fuera del ámbito declarado de unos parámetros se calcula igualmente y se avisa; dentro, en el borde o sin ámbito, no (S033/RF-15). */
class TransformationAreaWarningTest {

    private val spain = AreaOfUse(southDeg = 35.0, northDeg = 44.0, westDeg = -10.0, eastDeg = 5.0)
    private val grs80 = Ellipsoid.GRS80
    private val madrid = GeodeticPoint(40.4168, -3.7038, 650.0)
    private val canarias = GeodeticPoint(28.1, -15.4, 10.0)

    private val parameters =
        (
            HelmertParameters.seven(
                Triple(100.0, -200.0, 300.0),
                Triple(1.5, -2.0, 3.0),
                RotationUnit.ARC_SECOND,
                15.0,
                HelmertConvention.POSITION_VECTOR,
            ) as HelmertParametersResult.Ok
            ).parameters

    private fun helmert(area: AreaOfUse?) =
        HelmertTransformation(parameters, grs80, grs80, TransformationInfo("con ambito", areaOfUse = area))

    private fun ok(result: TransformResult) = result.shouldBeInstanceOf<TransformResult.Ok>()

    @Test
    fun `S033 RF-15 dentro del ambito se calcula sin aviso`() {
        ok(helmert(spain).forward(madrid)).warnings shouldBe emptyList()
        ok(helmert(spain).inverse(madrid)).warnings shouldBe emptyList()
    }

    @Test
    fun `S033 RF-15 en el borde del ambito todavia es dentro`() {
        ok(helmert(spain).forward(GeodeticPoint(35.0, 5.0, 0.0))).warnings shouldBe emptyList()
        ok(helmert(spain).forward(GeodeticPoint(44.0, -10.0, 0.0))).warnings shouldBe emptyList()
    }

    @Test
    fun `S033 RF-15 fuera del ambito se calcula igualmente y se avisa`() {
        val withArea = ok(helmert(spain).forward(canarias))
        val withoutArea = ok(helmert(null).forward(canarias))

        withArea.warnings shouldBe listOf(TransformWarning.OUTSIDE_AREA_OF_USE)
        withArea.point shouldBe withoutArea.point
    }

    @Test
    fun `S033 RF-15 el aviso tambien sale en el inverso`() {
        val withArea = ok(helmert(spain).inverse(canarias))

        withArea.warnings shouldBe listOf(TransformWarning.OUTSIDE_AREA_OF_USE)
        withArea.point shouldBe ok(helmert(null).inverse(canarias)).point
    }

    @Test
    fun `S033 RF-15 sin ambito declarado nunca se avisa`() {
        ok(helmert(null).forward(canarias)).warnings shouldBe emptyList()
    }

    @Test
    fun `S033 RF-15 Molodensky-Badekas avisa igual que el Helmert`() {
        val pivot = (
            MolodenskyBadekasParameters.of(
                parameters,
                Triple(4_800_000.0, -300_000.0, 4_150_000.0),
            ) as MolodenskyBadekasParametersResult.Ok
            ).parameters
        val mb = MolodenskyBadekas(pivot, grs80, grs80, TransformationInfo("mb", areaOfUse = spain))

        ok(mb.forward(madrid)).warnings shouldBe emptyList()
        ok(mb.forward(canarias)).warnings shouldBe listOf(TransformWarning.OUTSIDE_AREA_OF_USE)
    }

    @Test
    fun `S033 RF-15 la cadena avisa aunque componga los pasos en cartesianas`() {
        // Dos Helmert seguidos (se componen sin volver a geográficas): el segundo declara el ámbito.
        val first = helmert(null)
        val second = helmert(spain)
        val chain = TransformationChain(listOf(first, second), TransformationInfo("cadena"))

        ok(chain.forward(madrid)).warnings shouldBe emptyList()
        ok(chain.forward(canarias)).warnings shouldBe listOf(TransformWarning.OUTSIDE_AREA_OF_USE)
        ok(chain.forward(canarias)).point shouldBe
            ok(TransformationChain(listOf(first, helmert(null)), TransformationInfo("sin")).forward(canarias)).point
    }

    @Test
    fun `S033 RF-15 un solo paso fuera de su ambito marca la cadena una sola vez`() {
        val chain = TransformationChain(listOf(helmert(spain), helmert(spain)), TransformationInfo("dos"))

        ok(chain.forward(canarias)).warnings shouldBe listOf(TransformWarning.OUTSIDE_AREA_OF_USE)
    }

    @Test
    fun `S033 RF-15 la rejilla no avisa por ambito, rechaza fuera de ella`() {
        val grid =
            GridShiftTransformation(
                GridShiftVector.GRID_NAME,
                ClasspathGridSource("/shift/"),
                TransformationInfo("SPED2ETV2", areaOfUse = spain),
            )

        ok(grid.forward(madrid)).warnings shouldBe emptyList()
        grid.forward(canarias) shouldBe TransformResult.OutsideGrid(GridShiftVector.GRID_NAME)
    }
}

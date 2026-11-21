package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.Ellipsoid
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Cada transformación se describe con nombre, código EPSG, precisión declarada y ámbito, para la ficha del SC (S033/RF-14). */
class TransformationInfoTest {

    private val netherlands = AreaOfUse(southDeg = 50.75, northDeg = 53.7, westDeg = 3.2, eastDeg = 7.22)
    private val info = TransformationInfo("Amersfoort to ETRS89 (2)", epsgCode = 1066, accuracyM = 0.5, areaOfUse = netherlands)
    private val grs80 = Ellipsoid.GRS80

    private fun helmert() = (HelmertParameters.translations3(Triple(1.0, 2.0, 3.0)) as HelmertParametersResult.Ok).parameters

    @Test
    fun `S033 RF-14 el Helmert da su nombre, su codigo EPSG, su precision y su ambito`() {
        val t = HelmertTransformation(helmert(), grs80, grs80, info)

        t.info.name shouldBe "Amersfoort to ETRS89 (2)"
        t.info.epsgCode shouldBe 1066
        t.info.accuracyM shouldBe 0.5
        t.info.areaOfUse shouldBe netherlands
    }

    @Test
    fun `S033 RF-14 Molodensky-Badekas y la rejilla tambien`() {
        val pivot = (MolodenskyBadekasParameters.of(helmert(), Triple(1.0, 2.0, 3.0)) as MolodenskyBadekasParametersResult.Ok).parameters
        val mb = MolodenskyBadekas(pivot, grs80, grs80, info)
        val grid =
            GridShiftTransformation(
                "es_ign_SPED2ETV2.tif",
                ClasspathGridSource("/shift/"),
                TransformationInfo("ED50 a ETRS89 (SPED2ETV2)", 15932, 0.05),
            )

        mb.info shouldBe info
        grid.info.name shouldBe "ED50 a ETRS89 (SPED2ETV2)"
        grid.info.epsgCode shouldBe 15932
        grid.info.accuracyM shouldBe 0.05
        grid.info.areaOfUse shouldBe null
    }

    @Test
    fun `S033 RF-14 una cadena lleva su propia descripcion`() {
        val steps = ChainVector.all().first().steps
        val chain = TransformationChain(steps, TransformationInfo("ED50 a ETRS89 con ajuste", accuracyM = 0.1))

        chain.info.name shouldBe "ED50 a ETRS89 con ajuste"
        chain.info.epsgCode shouldBe null
        chain.info.accuracyM shouldBe 0.1
    }

    @Test
    fun `S033 RF-14 el codigo EPSG, la precision y el ambito son opcionales`() {
        val bare = TransformationInfo("Propia")

        bare.epsgCode shouldBe null
        bare.accuracyM shouldBe null
        bare.areaOfUse shouldBe null
    }
}

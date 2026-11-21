package es.topophone.core.geodesy.datum

import es.topophone.core.geodesy.GeodeticPoint
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Rejilla ausente, dañada o que no es de desplazamientos: se dice cuál falta y no se calcula sin ella (S033/RF-9). */
class GridShiftUnavailableTest {

    private val point = GeodeticPoint(40.4168, -3.7038, 650.0)

    private fun transformation(source: GridSource, name: String = "falta.tif") =
        GridShiftTransformation(name, source, TransformationInfo("prueba"))

    @Test
    fun `S033 RF-9 una rejilla no instalada se rechaza con su nombre`() {
        val t = transformation({ null }, "es_ign_SPED2ETV2.tif")

        t.forward(point) shouldBe TransformResult.GridUnavailable("es_ign_SPED2ETV2.tif")
    }

    @Test
    fun `S033 RF-9 una rejilla con bytes dañados se rechaza con su nombre`() {
        val garbage = ByteArray(500) { (it * 31).toByte() }

        transformation({ garbage }).forward(point) shouldBe TransformResult.GridUnavailable("falta.tif")
    }

    @Test
    fun `S033 RF-9 una rejilla vacia o truncada se rechaza con su nombre`() {
        val real = GridShiftTransformation::class.java.getResourceAsStream("/shift/es_ign_SPED2ETV2.tif")!!.use { it.readBytes() }

        transformation({ ByteArray(0) }).forward(point) shouldBe TransformResult.GridUnavailable("falta.tif")
        transformation({ real.copyOf(real.size / 3) }).forward(point) shouldBe TransformResult.GridUnavailable("falta.tif")
    }

    @Test
    fun `S033 RF-9 si la fuente lanza una excepcion de lectura no se propaga`() {
        val t = transformation({ throw java.io.IOException("disco") })

        t.forward(point) shouldBe TransformResult.GridUnavailable("falta.tif")
    }

    @Test
    fun `S033 RF-9 un fichero valido que no es de desplazamientos horizontales se rechaza`() {
        val geoid = GridShiftTransformation::class.java.getResourceAsStream("/geoid/es_ign_egm08-rednap.tif")!!.use { it.readBytes() }

        transformation({ geoid }, "es_ign_egm08-rednap.tif").forward(point) shouldBe
            TransformResult.GridUnavailable("es_ign_egm08-rednap.tif")
    }

    @Test
    fun `S033 RF-9 la rejilla instalada se lee una sola vez`() {
        var reads = 0
        val real = GridShiftTransformation::class.java.getResourceAsStream("/shift/es_ign_SPED2ETV2.tif")!!.use { it.readBytes() }
        val t = transformation({
            reads++
            real
        })

        repeat(5) { t.forward(point) }

        reads shouldBe 1
    }
}

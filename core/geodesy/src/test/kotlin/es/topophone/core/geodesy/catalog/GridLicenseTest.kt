package es.topophone.core.geodesy.catalog

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** Las licencias de las rejillas y lo que va en el APK (S034/RF-20, RNF-T14). */
class GridLicenseTest {
    private val catalog = CrsCatalog.bundled

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "core").isDirectory }

    @Test
    fun `S034 RF-20 las rejillas que van en la app son exactamente las marcadas como incluidas y todas tienen licencia abierta`() {
        val bundled = catalog.grids.values.filter { it.bundled }

        val expected = listOf("es_ign_SPED2ETV2.tif", "es_ign_egm08-rednap-canarias.tif", "es_ign_egm08-rednap.tif")
        bundled.map { it.name }.sorted() shouldBe expected
        bundled.filter { !it.license.open }.shouldBeEmpty()

        val shipped = listOf("geoid", "shift").flatMap { folder ->
            File(root, "core/geodesy/src/main/resources/$folder").listFiles()!!.map { it.name }
        }.sorted()
        shipped shouldBe bundled.map { it.name }.sorted()
    }

    @Test
    fun `S034 RF-20 las rejillas de licencia no clara se ofrecen con su fuente para consultarla y nunca van en la app`() {
        val unclear = catalog.grids.values.filter { !it.license.open }

        unclear.filter { it.bundled }.shouldBeEmpty()
        unclear.filter { it.sourceUrl.isEmpty() }.shouldBeEmpty()
    }

    @Test
    fun `S034 RF-20 toda rejilla del catalogo muestra su licencia y su atribucion`() {
        catalog.grids.values.filter { it.license.name.isEmpty() || it.source.isEmpty() }.shouldBeEmpty()
    }
}

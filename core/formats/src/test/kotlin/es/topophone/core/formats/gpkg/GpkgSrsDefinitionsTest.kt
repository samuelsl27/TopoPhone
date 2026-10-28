package es.topophone.core.formats.gpkg

import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** El sistema de coordenadas declarado en el fichero exportado, frente a lo que da pyproj (S053/RF-6, RF-7, RF-8; constitución nº 2). */
class GpkgSrsDefinitionsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    /** (código, nombre) de cada fila de `esperado-srs.csv`; la definición, que cambia con la versión de PROJ, no se compara. */
    private fun expected(): List<Pair<Int, String>> =
        File(root, "fixtures/formatos/gpkg-exportado/esperado-srs.csv").readLines().drop(1).filter { it.isNotBlank() }.map { line ->
            val match = Regex("""^(\d+),([^,]+),""").find(line).let { requireNotNull(it) }
            match.groupValues[1].toInt() to match.groupValues[2]
        }

    @Test
    fun `S053 RF-6 hay una definicion por cada sistema de pyproj con su codigo y su nombre`() {
        GpkgSrsDefinitionsData.ALL.map { it.first to it.second } shouldBe expected()
    }

    @Test
    fun `S053 RF-6 cada sistema del proyecto se declara con su codigo EPSG y su definicion`() {
        for (crs in ProjectCrs.entries) {
            val srs = GpkgSrsDefinitions.of(crs)

            srs.srsId shouldBe crs.epsg
            srs.organization shouldBe "EPSG"
            srs.organizationCode shouldBe crs.epsg
            srs.definition.startsWith(if (crs.isUtm) "PROJCS[" else "GEOGCS[") shouldBe true
            srs.definition.endsWith("""AUTHORITY["EPSG","${crs.epsg}"]]""") shouldBe true
        }
    }

    @Test
    fun `S053 RF-7 las geograficas son las del datum de los calculos y un proyecto ED50 declara ETRS89`() {
        GpkgSrsDefinitions.geographicOf(ProjectCrs.ETRS89_UTM30).srsId shouldBe 4258
        GpkgSrsDefinitions.geographicOf(ProjectCrs.ETRS89_GEOGRAPHIC).srsId shouldBe 4258
        GpkgSrsDefinitions.geographicOf(ProjectCrs.REGCAN95_UTM28).srsId shouldBe 4081
        GpkgSrsDefinitions.geographicOf(ProjectCrs.REGCAN95_GEOGRAPHIC).srsId shouldBe 4081
        for (crs in listOf(ProjectCrs.ED50_UTM29, ProjectCrs.ED50_UTM30, ProjectCrs.ED50_UTM31)) {
            GpkgSrsDefinitions.geographicOf(crs).srsId shouldBe 4258 // el punto guarda latitud y longitud en ETRS89 (D3)
        }
        GpkgSrsDefinitions.geographicOf(ProjectCrs.ETRS89_UTM30).definition.startsWith("GEOGCS[") shouldBe true
    }

    @Test
    fun `S053 RF-6 las tres filas obligatorias de GeoPackage son las de la norma`() {
        val rows = GpkgSrsDefinitions.mandatory

        rows.map { it.srsId } shouldBe listOf(-1, 0, 4326)
        rows[0].organization shouldBe "NONE"
        rows[0].definition shouldBe "undefined"
        rows[1].organization shouldBe "NONE"
        rows[2].organization shouldBe "EPSG"
        rows[2].definition.endsWith("""AUTHORITY["EPSG","4326"]]""") shouldBe true
    }

    @Test
    fun `S053 RF-8 un sistema sin codigo reconocido se declara con su definicion completa y sin autoridad`() {
        val wkt = """PROJCS["Obra local",GEOGCS["ETRS89"],PROJECTION["Transverse_Mercator"],UNIT["metre",1]]"""

        val srs = GpkgSrsDefinitions.custom("Obra local", wkt)

        srs.srsId shouldBe GpkgSrsDefinitions.CUSTOM_SRS_ID
        (srs.srsId >= 100_000) shouldBe true
        srs.organization shouldBe "NONE"
        srs.organizationCode shouldBe srs.srsId
        srs.definition shouldBe wkt
        srs.name shouldBe "Obra local"
        srs.isCustom shouldBe true
        GpkgSrsDefinitions.of(ProjectCrs.ETRS89_UTM30).isCustom shouldBe false
    }
}

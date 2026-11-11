package es.topophone.core.formats.shp

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** De lo que elige el usuario a las capas, los ficheros huérfanos y los errores (S030/RF-2 a RF-7). */
class ShpSelectionTest {

    private val selections = ShpFixtures.expected("esperado-seleccion.csv")
    private val zips = ShpFixtures.expected("esperado-zip.csv")

    private fun group(zip: String): ShpGrouping = ShpSelection.group(ShpFixtures.zipEntries(zip))

    @Test
    fun `S030 RF-2 los ZIP de ejemplo dan las capas y los ficheros asociados esperados`() {
        for (row in zips.filter { it.getValue("resultado") == "OK" }) {
            val grouping = group(row.getValue("archivo"))

            grouping.layers.map { it.name } shouldBe ShpFixtures.list(row.getValue("capas"))
        }
    }

    @Test
    fun `S030 RF-2 cada shp se asocia con el shx el dbf el prj y el cpg de su mismo nombre`() {
        val vertices = group("zip/completo.zip").layers.first { it.name == "vertices" }

        listOfNotNull(vertices.shx, vertices.dbf, vertices.prj, vertices.cpg).map { it.name } shouldBe
            listOf("vertices.shx", "vertices.dbf", "vertices.prj", "vertices.cpg")
        vertices.shp.name shouldBe "vertices.shp"
        group("zip/mayusculas.zip").layers.single().cpg shouldBe null
    }

    @Test
    fun `S030 RF-2 las mayusculas del nombre no cuentan y el nombre de la capa conserva las del shp`() {
        val layer = group("zip/mayusculas.zip").layers.single()

        layer.name shouldBe "PARCELAS"
        layer.dbf?.name shouldBe "PARCELAS.DBF"
        layer.prj?.name shouldBe "parcelas.prj"
    }

    @Test
    fun `S030 RF-2 un shp con otra extension y los ficheros de su nombre forman una capa`() {
        val picks = listOf("vertices.dat", "vertices.dbf", "vertices.prj").map { ShpFixtures.pick("otra-extension/$it") }

        val layer = ShpSelection.group(picks.map { it.asEntry() }).layers.single()

        layer.name shouldBe "vertices"
        layer.shp.name shouldBe "vertices.dat"
        layer.dbf?.name shouldBe "vertices.dbf"
    }

    @Test
    fun `S030 RF-7 dos capas con el mismo nombre en carpetas distintas llevan su carpeta`() {
        group("zip/carpetas-repetidas.zip").layers.map { it.name } shouldBe listOf("norte/parcelas", "sur/parcelas")
        // dentro de una sola carpeta no hace falta
        group("zip/subcarpeta.zip").layers.map { it.name } shouldBe listOf("vertices")
    }

    @Test
    fun `S030 RF-5 los shx dbf y prj sin su shp quedan como huerfanos con su nombre`() {
        for (row in zips) {
            if (row.getValue("resultado") != "OK") continue

            group(row.getValue("archivo")).orphans shouldBe ShpFixtures.list(row.getValue("huerfanos"))
        }
        group("zip/huerfanos.zip").orphans shouldBe listOf("otro.shx", "solo.dbf", "solo.prj")
    }

    @Test
    fun `S030 RF-5 los ficheros de metadatos estilos e indices no son huerfanos ni capas`() {
        val grouping = group("zip/completo.zip")

        grouping.orphans shouldBe emptyList()
        grouping.layers.size shouldBe 3 // LEEME.txt, vertices.sbn, vertices.shp.xml, __MACOSX/… y parcelas.qpj se ignoran
        grouping.dropped shouldBe emptyList()
    }

    @Test
    fun `S030 RF-6 un nombre con una ruta que sale de la carpeta se descarta y el resto se importa`() {
        val grouping = group("zip/ruta-peligrosa.zip")

        grouping.dropped shouldBe listOf("../escapa.shp", "/ruta/absoluta.dbf")
        grouping.layers.map { it.name } shouldBe listOf("ok")
        ShpSelection.group(listOf(ShpEntry("C:\\datos\\x.shp", ShpFixtures.head("puntos-25830/vertices.shp"), 1000))).dropped shouldBe
            listOf("C:\\datos\\x.shp")
    }

    @Test
    fun `S030 RF-3 sin ningun shp legible no hay capas`() {
        group("zip/sin-shp.zip").layers shouldBe emptyList()
        // un shp con la cabecera cortada tampoco es una capa
        ShpSelection.group(listOf(ShpEntry("x.shp", ByteArray(50), 50))).layers shouldBe emptyList()
    }

    @Test
    fun `S030 RF-3 y RF-4 las selecciones de ejemplo dan lo esperado`() {
        (selections.size >= 10) shouldBe true
        for (row in selections) {
            val picks = ShpFixtures.list(row.getValue("ficheros")).map(ShpFixtures::pick)
            val expectedLayers = ShpFixtures.list(row.getValue("capas"))
            val expectedOrphans = ShpFixtures.list(row.getValue("huerfanos"))

            when (val result = ShpSelection.check(picks)) {
                is ShpPickResult.Rejected -> result.reason.name shouldBe rejection(row.getValue("resultado"))
                is ShpPickResult.Loose -> {
                    val grouping = ShpSelection.group(result.picks.map { it.asEntry() })
                    if (grouping.layers.isEmpty()) {
                        row.getValue("resultado") shouldBe "SIN_SHP"
                    } else {
                        grouping.layers.map { it.name } shouldBe expectedLayers
                        grouping.orphans shouldBe expectedOrphans
                    }
                }
                is ShpPickResult.SingleZip -> {
                    val entries = ShpFixtures.zipEntries(row.getValue("ficheros"))
                    (row.getValue("resultado") == "VACIO") shouldBe entries.isEmpty()
                    if (entries.isNotEmpty()) ShpSelection.group(entries).layers.map { it.name } shouldBe expectedLayers
                }
            }
        }
    }

    @Test
    fun `S030 RF-4 un ZIP con otros ficheros o mas de un ZIP se rechaza sin leer nada mas`() {
        ShpSelection.check(listOf(ShpFixtures.pick("zip/completo.zip"), ShpFixtures.pick("puntos-25830/vertices.shp"))) shouldBe
            ShpPickResult.Rejected(ShpRejection.MIXED_SELECTION)
        ShpSelection.check(listOf(ShpFixtures.pick("zip/completo.zip"), ShpFixtures.pick("zip/huerfanos.zip"))) shouldBe
            ShpPickResult.Rejected(ShpRejection.MIXED_SELECTION)
    }

    @Test
    fun `S030 RF-3 un solo texto no es un Shapefile y unos ficheros sin shp piden el shp`() {
        ShpSelection.check(listOf(ShpFixtures.pick("zip/texto.zip"))) shouldBe ShpPickResult.Rejected(ShpRejection.NOT_SHAPEFILE)
        ShpSelection.check(listOf(ShpFixtures.pick("puntos-25830/vertices.dbf"), ShpFixtures.pick("puntos-25830/vertices.prj"))) shouldBe
            ShpPickResult.Rejected(ShpRejection.NO_SHP)
        ShpSelection.check(listOf(ShpFixtures.pick("puntos-25830/vertices.dbf"))) shouldBe ShpPickResult.Rejected(ShpRejection.NO_SHP)
        ShpSelection.check(emptyList()) shouldBe ShpPickResult.Rejected(ShpRejection.EMPTY)
        ShpSelection.check(listOf(ShpPick("vacio.shp", ByteArray(0), 0))) shouldBe ShpPickResult.Rejected(ShpRejection.EMPTY)
    }

    @Test
    fun `S030 RF-6 mas de 100 MB entre los ficheros elegidos se rechaza`() {
        val big = ShpPick("grande.shp", ShpFixtures.head("puntos-25830/vertices.shp"), ShpFileKind.MAX_PICKED_BYTES)
        val more = ShpPick("otro.dbf", ByteArray(100), 1)

        ShpSelection.check(listOf(big)) shouldBe ShpPickResult.Loose(listOf(big))
        ShpSelection.check(listOf(big, more)) shouldBe ShpPickResult.Rejected(ShpRejection.TOO_LARGE)
        val bigZip = ShpPick("grande.zip", ShpFixtures.head("zip/completo.zip"), ShpFileKind.MAX_PICKED_BYTES + 1)
        ShpSelection.check(listOf(bigZip)) shouldBe ShpPickResult.Rejected(ShpRejection.TOO_LARGE)
    }

    private fun ShpPick.asEntry() = ShpEntry(name, head, sizeBytes)

    private fun rejection(expected: String): String = when (expected) {
        "MEZCLA" -> "MIXED_SELECTION"
        "NO_ES_SHAPEFILE" -> "NOT_SHAPEFILE"
        "SIN_SHP" -> "NO_SHP"
        "VACIO" -> "EMPTY"
        else -> expected
    }
}

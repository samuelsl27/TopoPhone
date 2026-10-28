package es.topophone.core.survey

import es.topophone.core.formats.raster.RasterCrsDeclaration
import es.topophone.core.formats.raster.RasterCrsResolver
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.surface.RasterInfo
import es.topophone.core.model.surface.RasterTile
import es.topophone.core.model.surface.RasterValueType
import es.topophone.core.model.surface.Surface
import es.topophone.core.model.surface.SurfaceExtent
import es.topophone.core.model.surface.SurfaceHeight
import es.topophone.core.model.surface.SurfaceKind
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** La cota de un ráster en una posición del proyecto: cambio de sistema, bilineal estricta, unidad y geoide (S045/RF-9, RF-10, RF-21 a RF-23). */
class RasterElevationTest {

    /** Todos los bloques de un ráster en memoria; los que se quiten responden «no cargado». */
    private class InMemoryTiles(raster: LoadedRaster, valueType: RasterValueType, private val missing: Set<Pair<Int, Int>> = emptySet()) :
        RasterTileSource {
        private val tiles = HashMap<Pair<Int, Int>, RasterTile>()

        init {
            val columns = raster.header.columns
            var row = 0
            while (row < raster.header.rows) {
                val rows = minOf(RasterTile.EDGE, raster.header.rows - row)
                val band = raster.values.copyOfRange(row * columns, (row + rows) * columns)
                RasterTiler.cut(row, rows, columns, band, valueType).forEach { tiles[it.tileColumn to it.tileRow] = it.tile }
                row += rows
            }
        }

        override fun tile(tileColumn: Int, tileRow: Int): RasterTile? = if ((tileColumn to tileRow) in
            missing
        ) {
            null
        } else {
            tiles[tileColumn to tileRow]
        }
    }

    private fun surface(
        raster: LoadedRaster,
        source: CadSourceCrs,
        unit: LinearUnit,
        heightSystem: HeightSystem,
        valueType: RasterValueType,
    ): Surface {
        val h = raster.header
        val info = RasterInfo(h.columns, h.rows, h.originX, h.originY, h.stepX, h.stepY, valueType, heightSystem, 1, 0)
        return Surface(
            "r", "p", "mdt", raster.name, 0L, source, unit, SurfaceExtent(0.0, 1.0, 0.0, 1.0), 0.0, 0.0, 0, 0, 0,
            kind = SurfaceKind.RASTER, raster = info,
        )
    }

    private fun elevation(
        name: String,
        projectHeight: HeightSystem,
        rasterHeight: HeightSystem,
        crs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
        source: CadSourceCrs? = null,
        missing: Set<Pair<Int, Int>> = emptySet(),
    ): RasterElevation {
        val raster = RasterTestFiles.load(name)
        val info = RasterTestFiles.table("ficheros.csv").first { it.getValue("fichero") == name }
        val unit = if (info.getValue("unidad") == "ft") LinearUnit.INTERNATIONAL_FOOT else LinearUnit.METRE
        val valueType = if (info.getValue("tipo") == "f64") RasterValueType.FLOAT64 else RasterValueType.FLOAT32
        val declared = (RasterCrsResolver.resolve(raster.header.epsg) as? RasterCrsDeclaration.Supported)?.source ?: CadSourceCrs.Project
        return RasterElevation(
            crs,
            projectHeight,
            surface(raster, source ?: declared, unit, rasterHeight, valueType),
            InMemoryTiles(raster, valueType, missing),
        )
    }

    @Test
    fun `S045 RF-21 la cota de cada fichero de prueba coincide con el calculo aparte a menos de 1 mm`() {
        val queries = RasterTestFiles.table("consultas.csv")
        val cache = HashMap<String, RasterElevation>()
        var checked = 0
        for (q in queries) {
            val rasterHeight = HeightSystem.valueOf(q.getValue("altura_raster"))
            val projectHeight = HeightSystem.valueOf(q.getValue("altura_proyecto"))
            val elevation = cache.getOrPut("${q["fichero"]}|$rasterHeight|$projectHeight") {
                elevation(q.getValue("fichero"), projectHeight, rasterHeight)
            }

            val result = elevation.heightAt(q.getValue("este").toDouble(), q.getValue("norte").toDouble())

            val expected = q.getValue("cota_esperada_m")
            if (expected.isEmpty()) {
                result shouldBe SurfaceHeight.Outside
            } else {
                (result as SurfaceHeight.Value).metres shouldBe (expected.toDouble() plusOrMinus 1e-3)
            }
            checked++
        }
        (checked > 150) shouldBe true
    }

    @Test
    fun `S045 RF-23 en el centro de una celda la cota es el valor de esa celda`() {
        val raster = RasterTestFiles.load("f64-sin-tiras-punto.tif")
        val elevation = elevation("f64-sin-tiras-punto.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC)

        for ((col, row) in listOf(7 to 9, 0 to 0, 39 to 35, 13 to 27, 20 to 18)) {
            val e = raster.header.originX + col * raster.header.stepX
            val n = raster.header.originY - row * raster.header.stepY

            (elevation.heightAt(e, n) as SurfaceHeight.Value).metres shouldBe (raster.value(col, row) plusOrMinus 1e-9)
        }
    }

    @Test
    fun `S045 RF-22 el hueco sin dato y la media celda del borde son fuera de la superficie`() {
        val raster = RasterTestFiles.load("f32-sin-tiras-area.tif")
        val elevation = elevation("f32-sin-tiras-area.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC)
        val h = raster.header

        elevation.heightAt(h.originX + 10.5 * h.stepX, h.originY - 8.5 * h.stepY) shouldBe SurfaceHeight.Outside
        elevation.heightAt(h.originX + 10 * h.stepX, h.originY - 8 * h.stepY) shouldBe SurfaceHeight.Outside
        elevation.heightAt(h.originX - 0.5 * h.stepX, h.originY - 5 * h.stepY) shouldBe SurfaceHeight.Outside
        elevation.heightAt(h.originX + 39.5 * h.stepX, h.originY - 5 * h.stepY) shouldBe SurfaceHeight.Outside
        (elevation.heightAt(h.originX + 8.5 * h.stepX, h.originY - 8.5 * h.stepY) is SurfaceHeight.Value) shouldBe true
    }

    @Test
    fun `S045 RF-22 a un milimetro del borde cuenta como dentro y a dos no`() {
        val raster = RasterTestFiles.load("f32-sin-tiras-area.tif")
        val elevation = elevation("f32-sin-tiras-area.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC)
        val h = raster.header
        val n = h.originY - 5 * h.stepY

        (elevation.heightAt(h.originX - 0.0009, n) as SurfaceHeight.Value).metres shouldBe (raster.value(0, 5) plusOrMinus 1e-9)
        elevation.heightAt(h.originX - 0.002, n) shouldBe SurfaceHeight.Outside
        (elevation.heightAt(h.originX + 39 * h.stepX + 0.0009, n) as SurfaceHeight.Value).metres shouldBe
            (raster.value(39, 5) plusOrMinus 1e-9)
    }

    @Test
    fun `S045 RF-10 el mismo raster con el mismo sistema de alturas no consulta el geoide`() {
        val raster = RasterTestFiles.load("f32-sin-geoide.tif")
        val calls = intArrayOf(0)
        val surface = surface(raster, CadSourceCrs.Project, LinearUnit.METRE, HeightSystem.ORTHOMETRIC, RasterValueType.FLOAT32)
        val elevation = RasterElevation(
            ProjectCrs.ETRS89_UTM30,
            HeightSystem.ORTHOMETRIC,
            surface,
            InMemoryTiles(raster, RasterValueType.FLOAT32),
            peninsulaGeoid = {
                calls[0]++
                error("No se consulta")
            },
            canariasGeoid = {
                calls[0]++
                error("No se consulta")
            },
        )

        val result = elevation.heightAt(raster.header.originX + 4.5 * 10, raster.header.originY - 4.5 * 10)

        (result is SurfaceHeight.Value) shouldBe true
        calls[0] shouldBe 0
    }

    @Test
    fun `S045 RF-10 sin geoide en la zona la cota es fuera de la superficie`() {
        val elevation = elevation("f32-sin-geoide.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ELLIPSOIDAL)
        val raster = RasterTestFiles.load("f32-sin-geoide.tif")

        elevation.heightAt(raster.header.originX + 45, raster.header.originY - 45) shouldBe SurfaceHeight.Outside
    }

    @Test
    fun `S045 RF-9 las cotas en pies pasan a metros`() {
        val feet = elevation("f32-pies.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC)
        val metres = elevation("f32-sin-tiras-area.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC)
        val raster = RasterTestFiles.load("f32-pies.tif")
        val e = raster.header.originX + 7 * 10
        val n = raster.header.originY - 9 * 10

        (feet.heightAt(e, n) as SurfaceHeight.Value).metres shouldBe
            ((metres.heightAt(e, n) as SurfaceHeight.Value).metres * 0.3048 plusOrMinus 1e-9)
    }

    @Test
    fun `S045 RF-9 un raster declarado en pies topograficos de EE UU usa 1200 entre 3937`() {
        val raster = RasterTestFiles.load("f32-sin-tiras-area.tif")
        val surface = surface(raster, CadSourceCrs.Project, LinearUnit.US_SURVEY_FOOT, HeightSystem.ORTHOMETRIC, RasterValueType.FLOAT32)
        val elevation =
            RasterElevation(ProjectCrs.ETRS89_UTM30, HeightSystem.ORTHOMETRIC, surface, InMemoryTiles(raster, RasterValueType.FLOAT32))

        (elevation.heightAt(raster.header.originX + 70, raster.header.originY - 90) as SurfaceHeight.Value).metres shouldBe
            (raster.value(7, 9) * 1200.0 / 3937.0 plusOrMinus 1e-6)
    }

    @Test
    fun `S045 RF-21 un bloque que aun no esta en memoria da cargando y nunca fuera de la superficie`() {
        val raster = RasterTestFiles.load("f32-sin-tiras-area.tif")
        val elevation = elevation("f32-sin-tiras-area.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC, missing = setOf(0 to 0))

        elevation.heightAt(raster.header.originX + 70, raster.header.originY - 90) shouldBe SurfaceHeight.Loading
    }

    /** Un plano de 300 por 300 celdas: la bilineal de un plano es exacta, así que se comprueba en la esquina de cuatro bloques. */
    @Test
    fun `S045 RF-21 en la esquina de cuatro bloques la interpolacion usa celdas de bloques distintos`() {
        val columns = 300
        val rows = 300
        val values = DoubleArray(columns * rows) { 600.0 + 0.01 * (it % columns) + 0.02 * (it / columns) }
        val header = RasterTestFiles.load("esquina.asc").header.copy(columns = columns, rows = rows)
        val raster = LoadedRaster("plano", header, values)
        val elevation = RasterElevation(
            ProjectCrs.ETRS89_UTM30,
            HeightSystem.ORTHOMETRIC,
            surface(raster, CadSourceCrs.Project, LinearUnit.METRE, HeightSystem.ORTHOMETRIC, RasterValueType.FLOAT64),
            InMemoryTiles(raster, RasterValueType.FLOAT64),
        )

        for ((gx, gy) in listOf(127.5 to 127.5, 127.0 to 127.9, 128.0 to 128.0, 255.5 to 100.25, 299.0 to 299.0)) {
            val result = elevation.heightAt(header.originX + gx * header.stepX, header.originY - gy * header.stepY) as SurfaceHeight.Value
            result.metres shouldBe (600.0 + 0.01 * gx + 0.02 * gy plusOrMinus 1e-9)
        }
        elevation.heightAt(header.originX + 300 * header.stepX, header.originY) shouldBe SurfaceHeight.Outside
    }

    /** Un trozo real de MDT05 del IGN (`fixtures/raster/ORIGEN.md`) y lo esperado calculado con GDAL, no con TopoPhone. */
    @Test
    fun `S045 RF-21 un trozo real de MDT05 del IGN coincide con GDAL`() {
        val raster = RasterTestFiles.load("mdt05-ign-madrid.tif")
        val expected = RasterTestFiles.table("mdt05-ign-madrid-gdal.csv")

        raster.header.columns shouldBe 100
        raster.header.rows shouldBe 100
        raster.header.epsg shouldBe 25830
        raster.header.stepX shouldBe 5.0
        raster.header.valueType shouldBe RasterValueType.FLOAT32
        val surface = surface(raster, CadSourceCrs.Project, LinearUnit.METRE, HeightSystem.ORTHOMETRIC, RasterValueType.FLOAT32)
        val elevation = RasterElevation(
            ProjectCrs.ETRS89_UTM30,
            HeightSystem.ORTHOMETRIC,
            surface,
            InMemoryTiles(raster, RasterValueType.FLOAT32),
        )
        for (row in expected.filter { it["tipo"] == "celda" }) {
            raster.value(row.getValue("a").toInt(), row.getValue("b").toInt()) shouldBe row.getValue("valor_esperado").toDouble()
        }
        val bilinear = expected.filter { it["tipo"] == "bilineal" }
        bilinear.size shouldBe 10
        for (row in bilinear) {
            val result = elevation.heightAt(row.getValue("a").toDouble(), row.getValue("b").toDouble())
            (result as SurfaceHeight.Value).metres shouldBe (row.getValue("valor_esperado").toDouble() plusOrMinus 1e-6)
        }
    }

    @Test
    fun `S045 RF-8 un raster que declara el sistema del proyecto y uno que no dan la misma cota`() {
        val declared =
            elevation(
                "f32-sin-tiras-area.tif",
                HeightSystem.ORTHOMETRIC,
                HeightSystem.ORTHOMETRIC,
                source = CadSourceCrs.Utm(GeodeticDatum.ETRS89, 30),
            )
        val project = elevation("f32-sin-tiras-area.tif", HeightSystem.ORTHOMETRIC, HeightSystem.ORTHOMETRIC, source = CadSourceCrs.Project)

        for ((e, n) in listOf(440075.0 to 4473915.0, 440123.4 to 4473800.6)) {
            val a = (declared.heightAt(e, n) as SurfaceHeight.Value).metres
            val b = (project.heightAt(e, n) as SurfaceHeight.Value).metres
            a shouldBe (b plusOrMinus 1e-6)
        }
    }
}

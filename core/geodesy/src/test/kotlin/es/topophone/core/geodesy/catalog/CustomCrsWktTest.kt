package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeodesyFixtures
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.LinearUnit
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/**
 * El WKT1 de un SC personalizado (S035/RF-23): idéntico al de `personalizado_wkt.csv`, que `tools/geodesia/test_personalizado_wkt.py` comprueba contra
 * PROJ (leyéndolo da las mismas coordenadas que los parámetros sueltos, ≤ 1 mm).
 */
class CustomCrsWktTest {
    private val catalog = CrsCatalog.bundled

    private fun transformation(type: String, v: List<Double>): CustomTransformation {
        val values = HelmertValues(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        return when (type) {
            "nula" -> CustomTransformation.None
            "helmert3" -> CustomTransformation.Helmert(HelmertKind.TRANSLATIONS_3, RotationConvention.POSITION_VECTOR, values)
            "helmert7pv" -> CustomTransformation.Helmert(HelmertKind.SEVEN, RotationConvention.POSITION_VECTOR, values)
            "helmert7cf" -> CustomTransformation.Helmert(HelmertKind.SEVEN, RotationConvention.COORDINATE_FRAME, values)
            else -> error("Transformación desconocida: $type")
        }
    }

    private fun definitionOf(row: List<String>): CrsDefinition {
        val draft = CustomCrsDraft(
            name = row[0],
            semiMajorAxisM = row[3].toDouble(),
            inverseFlattening = row[4].toDouble(),
            method = row[1],
            parameters = row[2].split(';').associate { it.substringBefore('=') to it.substringAfter('=').toDouble() },
            transformation = transformation(row[5], row.subList(6, 13).map { it.toDouble() }),
        )
        return CustomCrsBuilder.build(draft, catalog).shouldBeInstanceOf<CustomBuildResult.Ready>().definition
    }

    @Test
    fun `S035 RF-23 el WKT de cada caso es identico al de la plantilla contrastada con PROJ`() {
        val vectors = GeodesyFixtures.rows("personalizado.csv").filter { it[13] == "1" }
        val golden = String(GeodesyFixtures.bytes("personalizado_wkt.csv"), Charsets.UTF_8).lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map { it.split('\t') }.associate { it[0] to it[1] }
        vectors.size shouldBeGreaterThan 7

        val failures = vectors.mapNotNull { row ->
            val wkt = CustomCrsWkt.wkt1(definitionOf(row))
            "${row[0]}: $wkt".takeIf { wkt != golden.getValue(row[0]) }
        }
        failures.shouldBeEmpty()
    }

    @Test
    fun `S035 RF-23 un SC geografico es solo un GEOGCS`() {
        val row = GeodesyFixtures.rows("personalizado.csv").first()
        val geo = definitionOf(row).copy(projected = false, method = "geo", parameters = emptyMap())

        val wkt = CustomCrsWkt.wkt1(geo).shouldNotBeNull()

        wkt shouldStartWith "GEOGCS[\"Personalizado\""
        wkt shouldNotContain "PROJCS"
    }

    @Test
    fun `S035 RF-23 las unidades en pies se declaran con su factor`() {
        val definition = definitionOf(
            GeodesyFixtures.rows("personalizado.csv").first(),
        ).copy(unitMetres = LinearUnit.INTERNATIONAL_FOOT.metres)

        CustomCrsWkt.wkt1(definition).shouldNotBeNull() shouldContain "UNIT[\"foot\",0.3048]]"
    }

    @Test
    fun `S035 RF-23 un meridiano de origen distinto de Greenwich se escribe`() {
        val definition = definitionOf(GeodesyFixtures.rows("personalizado.csv").first()).copy(primeMeridianDeg = -3.687938)

        CustomCrsWkt.wkt1(definition).shouldNotBeNull() shouldContain "PRIMEM[\"Custom\",-3.687938]"
    }

    @Test
    fun `S035 RF-23 el nombre no puede romper el WKT con comillas`() {
        val definition = definitionOf(GeodesyFixtures.rows("personalizado.csv").first()).copy(name = "La \"Obra\"\nnorte")

        CustomCrsWkt.wkt1(definition).shouldNotBeNull() shouldStartWith "PROJCS[\"La 'Obra' norte\""
    }

    @Test
    fun `S035 RF-23 una rejilla, Molodensky-Badekas o una cadena no se expresan en TOWGS84 y se dice`() {
        val base = CustomCrsDraft(
            name = "X",
            semiMajorAxisM = 6378137.0,
            inverseFlattening = 298.257222101,
            method = "tm",
            parameters = mapOf(
                "latitudeOfOriginDeg" to 0.0,
                "longitudeOfOriginDeg" to -3.0,
                "scaleFactor" to 0.9996,
                "falseEasting" to 500000.0,
                "falseNorthing" to 0.0,
            ),
        )
        val grid = catalog.grids.values.first { it.kind == GridKind.HORIZONTAL }.name
        val mb = CustomTransformation.MolodenskyBadekas(
            RotationConvention.POSITION_VECTOR,
            HelmertValues(1.0, 2.0, 3.0),
            4.0e6,
            1.0e5,
            4.8e6,
        )
        val cases = listOf(
            CustomTransformation.Grid(grid),
            mb,
            CustomTransformation.Kept("null|null"),
        )

        cases.forEach { t ->
            val d = CustomCrsBuilder.build(base.copy(transformation = t), catalog) { true }.let {
                when (it) {
                    is CustomBuildResult.Ready -> it.definition
                    is CustomBuildResult.NeedsGrids -> it.definition
                    is CustomBuildResult.Invalid -> error("${it.errors}")
                }
            }
            CustomCrsWkt.datumShiftExpressed(d) shouldBe false
            CustomCrsWkt.wkt1(d).shouldNotBeNull() shouldNotContain "TOWGS84"
        }
        CustomCrsWkt.datumShiftExpressed(
            CustomCrsBuilder.build(base, catalog).shouldBeInstanceOf<CustomBuildResult.Ready>().definition,
        ) shouldBe true
    }

    @Test
    fun `S035 RF-23 un metodo sin forma en WKT1 da null`() {
        val definition = definitionOf(GeodesyFixtures.rows("personalizado.csv").first()).copy(
            method = "polarc",
            parameters = mapOf(
                "latitudeOfStandardParallelDeg" to -71.0,
                "longitudeOfOriginDeg" to 0.0,
                "eastingAtFalseOrigin" to 0.0,
                "northingAtFalseOrigin" to 0.0,
            ),
        )

        CustomCrsWkt.wkt1(definition).shouldBeNull()
    }
}

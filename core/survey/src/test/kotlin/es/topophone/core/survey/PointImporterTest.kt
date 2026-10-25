package es.topophone.core.survey

import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.ImportCoordinates
import es.topophone.core.model.point.ImportDecimal
import es.topophone.core.model.point.ImportErrorReason
import es.topophone.core.model.point.ImportOptions
import es.topophone.core.model.point.ImportSeparator
import es.topophone.core.model.point.PointOrigin
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.math.BigDecimal
import kotlin.math.cos
import org.junit.jupiter.api.Test

/** De una fila del fichero a un punto: unidades, SC, alturas y errores, frente a pyproj (S028/RF-12 a RF-16, RF-20, RF-23, RF-27). */
class PointImporterTest {

    private val importer = PointImporter()
    private val etrs89Utm30 = ProjectCrs.ETRS89_UTM30

    /** Una fila de `esperado.csv`: lo escrito en metros (o grados) y lo que da pyproj. */
    private data class Expected(
        val file: String,
        val line: Int,
        val status: String,
        val name: String,
        val description: String,
        val form: EntryForm,
        val unit: LinearUnit,
        val crs: ProjectCrs,
        val heights: HeightSystem,
        val heightKind: HeightKind,
        val first: Double?,
        val second: Double?,
        val height: Double?,
        val latitude: Double?,
        val longitude: Double?,
        val ellipsoidal: Double?,
        val easting: Double?,
        val northing: Double?,
        val projectHeight: Double?,
    )

    /** Los registros de un CSV con comillas, que pueden llevar comas y saltos de línea dentro. */
    private fun csvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> cell.append('"').also { i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> cells.add(cell.toString()).also { cell.setLength(0) }
                c == '\n' && !quoted -> {
                    cells.add(cell.toString())
                    cell.setLength(0)
                    records.add(cells)
                    cells = mutableListOf()
                }
                c == '\r' && !quoted -> Unit
                else -> cell.append(c)
            }
            i++
        }
        return records
    }

    private val expected: List<Expected> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        val text = File(root, "fixtures/formatos/csv-importacion/esperado.csv").readText(Charsets.UTF_8)
            .lines().dropWhile { it.startsWith("#") }.joinToString("\n")
        csvRecords(text)
            .drop(1) // la cabecera
            .map { c ->
                fun d(i: Int) = c[i].takeIf { it.isNotEmpty() }?.toDouble()
                Expected(
                    file = c[0],
                    line = c[1].toInt(),
                    status = c[2],
                    name = c[3],
                    description = c[4],
                    form = EntryForm.valueOf(c[5]),
                    unit = when (c[6]) {
                        "m" -> LinearUnit.METRE
                        "ft" -> LinearUnit.INTERNATIONAL_FOOT
                        else -> LinearUnit.US_SURVEY_FOOT
                    },
                    crs = ProjectCrs.of(GeodeticDatum.valueOf(c[7]), c[8].toInt()).shouldNotBeNull(),
                    heights = HeightSystem.valueOf(c[9]),
                    heightKind = HeightKind.valueOf(c[10]),
                    first = d(11),
                    second = d(12),
                    height = d(13),
                    latitude = d(14),
                    longitude = d(15),
                    ellipsoidal = d(16),
                    easting = d(17),
                    northing = d(18),
                    projectHeight = d(19),
                )
            }
    }

    private fun text(valueM: Double?, unit: LinearUnit) = valueM?.let { BigDecimal.valueOf(unit.fromMetres(it)).toPlainString() } ?: ""

    private fun optionsOf(e: Expected) = ImportOptions(
        separator = ImportSeparator.SEMICOLON,
        decimal = ImportDecimal.POINT,
        mapping = ColumnMapping.penzd(5),
        coordinates = ImportCoordinates(e.form, e.heightKind),
        unit = e.unit,
    )

    private fun cellsOf(e: Expected): List<String> {
        val geographic = e.form == EntryForm.GEOGRAPHIC
        val first = if (geographic) e.first?.let { BigDecimal.valueOf(it).toPlainString() } else text(e.first, e.unit)
        val second = if (geographic) e.second?.let { BigDecimal.valueOf(it).toPlainString() } else text(e.second, e.unit)
        return listOf(e.name, first.orEmpty(), second.orEmpty(), text(e.height, e.unit), e.description)
    }

    private fun valid(
        cells: List<String>,
        options: ImportOptions,
        crs: ProjectCrs = etrs89Utm30,
        heights: HeightSystem = HeightSystem.ORTHOMETRIC,
    ) = importer.convert(cells, options, crs, heights).shouldBeInstanceOf<ImportRowResult.Valid>()

    private fun reason(cells: List<String>, options: ImportOptions, crs: ProjectCrs = etrs89Utm30) =
        importer.convert(cells, options, crs, HeightSystem.ORTHOMETRIC).shouldBeInstanceOf<ImportRowResult.Invalid>().reason

    private val project = ImportOptions(mapping = ColumnMapping.penzd(5))

    private fun metresOfDegrees(delta: Double, latitudeDeg: Double, longitude: Boolean) =
        delta * 111_319.49 * (if (longitude) cos(Math.toRadians(latitudeDeg)) else 1.0)

    @Test
    fun `S028 RF-12 cada fila valida del esperado da lo mismo que pyproj a menos de 1 mm`() {
        val rows = expected.filter { it.status == "OK" }
        rows.size shouldBe 57

        rows.forEach { e ->
            val result = importer.convert(cellsOf(e), optionsOf(e), e.crs, e.heights).shouldBeInstanceOf<ImportRowResult.Valid>()
            val c = result.coordinates

            result.name shouldBe e.name
            result.description shouldBe e.description
            metresOfDegrees(c.ground.latitudeDeg - e.latitude!!, e.latitude, longitude = false) shouldBe (0.0 plusOrMinus 1e-3)
            metresOfDegrees(c.ground.longitudeDeg - e.longitude!!, e.latitude, longitude = true) shouldBe (0.0 plusOrMinus 1e-3)
            if (e.ellipsoidal ==
                null
            ) {
                c.ground.ellipsoidalHeightM.shouldBeNull()
            } else {
                c.ground.ellipsoidalHeightM!! shouldBe
                    (e.ellipsoidal plusOrMinus 1e-3)
            }
            c.easting!! shouldBe (e.easting!! plusOrMinus 1e-3)
            c.northing!! shouldBe (e.northing!! plusOrMinus 1e-3)
            if (e.projectHeight == null) c.height.shouldBeNull() else c.height!! shouldBe (e.projectHeight plusOrMinus 1e-3)
        }
    }

    @Test
    fun `S028 RF-12 lo escrito en el SC del proyecto se guarda tal cual, sin la ida y vuelta, y en metros`() {
        val e = expected.first { it.file == "etrs89-utm30-pies.csv" && it.line == 2 }
        val result = valid(cellsOf(e), optionsOf(e), e.crs)

        result.typed.form shouldBe EntryForm.PROJECT_CRS
        result.typed.first shouldBe (e.first!! plusOrMinus 1e-9)
        result.typed.second shouldBe (e.second!! plusOrMinus 1e-9)
        result.typed.height!! shouldBe (e.height!! plusOrMinus 1e-9)
        result.coordinates.easting shouldBe result.typed.first
        result.coordinates.northing shouldBe result.typed.second
    }

    @Test
    fun `S028 RF-12 las coordenadas no se redondean`() {
        val result = valid(listOf("1", "440297.987535426412", "4474244.001132506123", "646.3773331296773", "x"), project)

        result.typed.first shouldBe 440297.987535426412
        result.typed.second shouldBe 4474244.001132506123
        result.typed.height shouldBe 646.3773331296773
    }

    @Test
    fun `S028 RF-12 en geograficas, la latitud y la longitud se guardan en grados`() {
        val options = project.copy(coordinates = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT))
        val result = valid(listOf("1", "40.41668", "-3.70371", "646.377", ""), options)

        result.typed.form shouldBe EntryForm.GEOGRAPHIC
        result.typed.first shouldBe 40.41668
        result.typed.second shouldBe -3.70371
        result.coordinates.ground.latitudeDeg shouldBe (40.41668 plusOrMinus 1e-12)
    }

    @Test
    fun `S028 RF-13 la altura elipsoidal solo se guarda como tal en geograficas`() {
        val options = project.copy(coordinates = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL))
        val result = valid(listOf("1", "40.41668", "-3.70371", "697.489", ""), options)

        result.typed.heightKind shouldBe HeightKind.ELLIPSOIDAL
        result.coordinates.ground.ellipsoidalHeightM shouldBe 697.489
    }

    @Test
    fun `S028 RF-14 pies, pies de EE UU y metros se pasan a metros`() {
        val cells = listOf("1", "1000000", "14000000", "100", "x")

        valid(listOf("1", "304800", "4267200", "30.48", "x"), project.copy(unit = LinearUnit.METRE)).typed.first shouldBe 304_800.0
        valid(cells, project.copy(unit = LinearUnit.INTERNATIONAL_FOOT)).typed.first shouldBe (304_800.0 plusOrMinus 1e-6)
        valid(cells, project.copy(unit = LinearUnit.US_SURVEY_FOOT)).typed.second shouldBe (14_000_000.0 * 1200.0 / 3937.0 plusOrMinus 1e-6)
        valid(cells, project.copy(unit = LinearUnit.INTERNATIONAL_FOOT)).typed.height!! shouldBe (30.48 plusOrMinus 1e-9)
    }

    @Test
    fun `S028 RF-14 en geograficas la unidad solo cambia la altura, no los grados`() {
        val options = project.copy(
            coordinates = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT),
            unit = LinearUnit.INTERNATIONAL_FOOT,
        )
        val result = valid(listOf("1", "40.41668", "-3.70371", "100", ""), options)

        result.typed.first shouldBe 40.41668
        result.typed.height!! shouldBe (30.48 plusOrMinus 1e-9)
    }

    @Test
    fun `S028 RF-15 una altura vacia o que no es un numero da un punto sin altura`() {
        valid(listOf("1", "440297.988", "4474244.001", "", "x"), project).typed.height.shouldBeNull()
        valid(listOf("1", "440297.988", "4474244.001", "n/d", "x"), project).typed.height.shouldBeNull()
        valid(listOf("1", "440297.988", "4474244.001", "  ", "x"), project).coordinates.ground.ellipsoidalHeightM.shouldBeNull()
    }

    @Test
    fun `S028 RF-15 una fila sin la columna de altura o sin la de descripcion tambien es valida`() {
        val result = valid(listOf("1", "440297.988", "4474244.001"), project)

        result.typed.height.shouldBeNull()
        result.description shouldBe ""
        valid(listOf("1", "440297.988", "4474244.001", "646.377"), project).description shouldBe ""
    }

    @Test
    fun `S028 RF-16 un cero es una altura salvo que se marque tratarlo como sin altura`() {
        val cells = listOf("1", "440297.988", "4474244.001", "0.000", "x")

        valid(cells, project).typed.height shouldBe 0.0
        valid(cells, project.copy(zeroAsNoHeight = true)).typed.height.shouldBeNull()
        valid(cells, project.copy(zeroAsNoHeight = true)).coordinates.ground.ellipsoidalHeightM.shouldBeNull()
        valid(listOf("1", "440297.988", "4474244.001", "-0", "x"), project.copy(zeroAsNoHeight = true)).typed.height.shouldBeNull()
        valid(listOf("1", "440297.988", "4474244.001", "0.001", "x"), project.copy(zeroAsNoHeight = true)).typed.height shouldBe 0.001
    }

    @Test
    fun `S028 RF-20 el nombre y la descripcion se recortan y los ceros a la izquierda se conservan`() {
        val result = valid(listOf("  001 ", "440297.988", "4474244.001", "1", "  Pozo  de registro  "), project)

        result.name shouldBe "001"
        result.description shouldBe "Pozo  de registro"
    }

    @Test
    fun `S028 RF-20 un nombre que no es un numero tampoco se toca`() {
        valid(listOf("P-01A", "440297.988", "4474244.001", "1", ""), project).name shouldBe "P-01A"
    }

    @Test
    fun `S028 RF-10 el orden de las columnas lo manda la asignacion, PNEZD con el Norte primero`() {
        val options = project.copy(mapping = ColumnMapping.pnezd(5))
        val result = valid(listOf("1", "4474244.001", "440297.988", "646.377", "Esquina"), options)

        result.typed.first shouldBe 440297.988
        result.typed.second shouldBe 4474244.001
        result.description shouldBe "Esquina"
    }

    @Test
    fun `S028 RF-11 una asignacion con las columnas desordenadas y otras ignoradas`() {
        val mapping = ColumnMapping(
            listOf(ColumnRole.IGNORE, ColumnRole.DESCRIPTION, ColumnRole.SECOND, ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.HEIGHT),
        )
        val result = valid(listOf("x", "Esquina", "4474244.001", "7", "440297.988", "646.377"), project.copy(mapping = mapping))

        result.name shouldBe "7"
        result.typed.first shouldBe 440297.988
        result.typed.second shouldBe 4474244.001
        result.typed.height shouldBe 646.377
        result.description shouldBe "Esquina"
    }

    @Test
    fun `S028 RF-8 la coma decimal se lee con la coma y no con el punto`() {
        val options = project.copy(decimal = ImportDecimal.COMMA, separator = ImportSeparator.SEMICOLON)

        valid(listOf("1", "440297,988", "4474244,001", "646,377", "x"), options).typed.first shouldBe 440297.988
        reason(listOf("1", "440297.988", "4474244,001", "646,377", "x"), options) shouldBe ImportErrorReason.NOT_A_NUMBER
    }

    @Test
    fun `S028 RF-23 una fila sin nombre es erronea por el nombre`() {
        reason(listOf("", "440297.988", "4474244.001", "1", "x"), project) shouldBe ImportErrorReason.MISSING_NAME
        reason(listOf("   ", "440297.988", "4474244.001", "1", "x"), project) shouldBe ImportErrorReason.MISSING_NAME
    }

    @Test
    fun `S028 RF-23 una coordenada que no es un numero es erronea`() {
        reason(listOf("1", "abc", "4474244.001", "1", "x"), project) shouldBe ImportErrorReason.NOT_A_NUMBER
        reason(listOf("1", "440297.988", "", "1", "x"), project) shouldBe ImportErrorReason.NOT_A_NUMBER
        reason(listOf("1", "440.297,988", "4474244.001", "1", "x"), project) shouldBe ImportErrorReason.NOT_A_NUMBER
    }

    @Test
    fun `S028 RF-23 una fila con menos columnas de las necesarias es erronea`() {
        reason(listOf("1", "440297.988"), project) shouldBe ImportErrorReason.MISSING_COLUMNS
        reason(listOf("1"), project) shouldBe ImportErrorReason.MISSING_COLUMNS
        reason(emptyList(), project) shouldBe ImportErrorReason.MISSING_COLUMNS
    }

    @Test
    fun `S028 RF-23 una asignacion sin nombre o sin coordenadas da columnas que faltan`() {
        val noName = project.copy(mapping = ColumnMapping(listOf(ColumnRole.IGNORE, ColumnRole.FIRST, ColumnRole.SECOND)))
        val noSecond = project.copy(mapping = ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST)))

        reason(listOf("1", "440297.988", "4474244.001"), noName) shouldBe ImportErrorReason.MISSING_COLUMNS
        reason(listOf("1", "440297.988", "4474244.001"), noSecond) shouldBe ImportErrorReason.MISSING_COLUMNS
    }

    @Test
    fun `S028 RF-23 el nombre vacio va antes que las coordenadas malas y las columnas que faltan van antes que el nombre`() {
        reason(listOf("", "abc", "def"), project) shouldBe ImportErrorReason.MISSING_NAME
        reason(listOf(""), project) shouldBe ImportErrorReason.MISSING_COLUMNS
    }

    @Test
    fun `S028 RF-23 un punto fuera del ambito del SC del proyecto es erroneo`() {
        reason(listOf("E3", "100.000", "100.000", "5.000", "Fuera del huso"), project) shouldBe ImportErrorReason.OUTSIDE_CRS_AREA
        val geographic = project.copy(coordinates = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT))
        reason(listOf("E3", "38.311862259118755", "-11.791081909012805", "1", ""), geographic) shouldBe ImportErrorReason.OUTSIDE_CRS_AREA
    }

    @Test
    fun `S028 RF-23 una altitud fuera del modelo de geoide es erronea y sin altura se importa igualmente`() {
        val crs = ProjectCrs.ETRS89_UTM31
        val withHeight = listOf("G1", "662514.3131878123", "4467374.061378814", "742.1401", "")

        reason(withHeight, project, crs) shouldBe ImportErrorReason.OUTSIDE_GEOID
        valid(withHeight.toMutableList().also { it[3] = "" }, project, crs).typed.height.shouldBeNull()
    }

    @Test
    fun `S028 RF-23 las filas con error del fichero de errores dan el motivo del esperado`() {
        val rows = mapOf(
            3 to listOf("", "725695,707", "4372210,140", "15,250", "Sin nombre"),
            4 to listOf("E1", "abc", "4372210,140", "15,250", "Este con letras"),
            5 to listOf("E2", "440290,458"),
            6 to listOf("E3", "100,000", "100,000", "5,000", "Fuera del huso"),
            10 to listOf("E4", "440.290,458", "4372210,140", "15,250", "Miles con punto"),
        )
        val options = project.copy(decimal = ImportDecimal.COMMA, separator = ImportSeparator.SEMICOLON)

        rows.forEach { (line, cells) ->
            val status = expected.first { it.file == "etrs89-utm30-errores.csv" && it.line == line }.status

            reason(cells, options).name shouldBe status
        }
    }

    @Test
    fun `S028 RF-15 las filas sin cota y con cero del fichero de errores`() {
        val options = project.copy(decimal = ImportDecimal.COMMA, separator = ImportSeparator.SEMICOLON)

        valid(listOf("4", "235778,520", "4142218,730", "", "Sin cota"), options).coordinates.height.shouldBeNull()
        valid(listOf("3", "675782,399", "4612936,528", "0,000", "Árbol ñandú"), options).coordinates.height shouldBe 0.0
    }

    @Test
    fun `S028 RF-27 el punto importado es de origen importado, sin medida ni jalon, con su importacion`() {
        val result = valid(listOf("1", "440297.988", "4474244.001", "646.377", "Esquina"), project)
        val point = result.toPoint(id = "id-1", projectId = "p1", importId = "imp-1", createdAtMs = 1234L)

        point.id shouldBe "id-1"
        point.projectId shouldBe "p1"
        point.name shouldBe "1"
        point.description shouldBe "Esquina"
        point.origin shouldBe PointOrigin.IMPORTED
        point.importId shouldBe "imp-1"
        point.createdAtMs shouldBe 1234L
        point.measurement.shouldBeNull()
        point.typed shouldBe result.typed
        point.coordinates shouldBe result.coordinates
        point.edited shouldBe false
        point.hidden shouldBe false
        point.copyOf.shouldBeNull()
        point.deletedAtMs.shouldBeNull()
        point.staked.shouldBeNull()
    }

    @Test
    fun `S028 RF-27 un punto con otro nombre por ser repetido conserva el resto`() {
        val result = valid(listOf("1", "440297.988", "4474244.001", "", "Esquina"), project)
        val point = result.toPoint(id = "id-2", projectId = "p1", importId = "imp-1", createdAtMs = 5L, name = "2")

        point.name shouldBe "2"
        point.description shouldBe "Esquina"
        point.typed shouldBe result.typed
    }
}

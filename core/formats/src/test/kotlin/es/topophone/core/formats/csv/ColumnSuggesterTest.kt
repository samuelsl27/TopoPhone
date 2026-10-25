package es.topophone.core.formats.csv

import es.topophone.core.model.point.ColumnMapping
import es.topophone.core.model.point.ColumnRole
import es.topophone.core.model.point.EntryForm
import es.topophone.core.model.point.HeightKind
import es.topophone.core.model.point.ImportCoordinates
import es.topophone.core.model.point.ImportEncoding
import es.topophone.core.model.point.MappingProblem
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.io.StringReader
import org.junit.jupiter.api.Test

/** La asignación de columnas que se propone al abrir el fichero (S028/RF-10, RF-11). */
class ColumnSuggesterTest {

    private val project = ImportCoordinates(EntryForm.PROJECT_CRS, HeightKind.PROJECT)
    private val ellipsoidal = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.ELLIPSOIDAL)
    private val geographicProjectHeight = ImportCoordinates(EntryForm.GEOGRAPHIC, HeightKind.PROJECT)

    private fun suggest(header: List<String>?, coordinates: ImportCoordinates = project, columns: Int = header?.size ?: 0) =
        ColumnSuggester.suggest(header, columns, coordinates)

    private fun s012Header(name: String): List<String> {
        val text = TextDecoding.decode(ImportFixtures.s012(name), ImportEncoding.UTF8)
        return DelimitedReader.rows(StringReader(text), DelimitedReader.detectSeparator(text)).first().cells
    }

    @Test
    fun `S028 RF-10 reconoce la cabecera en ingles Name Easting Northing Elevation Description`() {
        suggest(listOf("Name", "Easting", "Northing", "Elevation", "Description")) shouldBe ColumnMapping.penzd(5)
    }

    @Test
    fun `S028 RF-10 reconoce la cabecera en espanol, con acentos y mayusculas`() {
        suggest(listOf("PUNTO", "Este", "Norte", "Altura", "Descripción")) shouldBe ColumnMapping.penzd(5)
        suggest(listOf("Nombre", "Este", "Norte", "Cota", "Código")) shouldBe ColumnMapping.penzd(5)
        suggest(listOf("Id", "X", "Y", "Z", "Notas")) shouldBe ColumnMapping.penzd(5)
    }

    @Test
    fun `S028 RF-10 reconoce las columnas en otro orden, el Norte antes que el Este`() {
        suggest(listOf("Point", "Northing", "Easting", "Height")) shouldBe ColumnMapping.pnezd(4)
    }

    @Test
    fun `S028 RF-10 ignora las unidades entre parentesis o corchetes y los signos`() {
        suggest(listOf("Name", "Easting (m)", "Northing [m]", "Elevation (m)")) shouldBe ColumnMapping.penzd(4)
        suggest(listOf("  Nombre ", "ESTE.", "-norte-")) shouldBe ColumnMapping.penzd(3)
    }

    @Test
    fun `S028 RF-10 reconoce las columnas del CSV de S012 con el SC del proyecto`() {
        val header = s012Header("todas_coma_punto.csv")
        val mapping = suggest(header, project)

        mapping.roleOf(header.indexOf("Name")) shouldBe ColumnRole.NAME
        mapping.roleOf(header.indexOf("Description")) shouldBe ColumnRole.DESCRIPTION
        mapping.roleOf(header.indexOf("Easting")) shouldBe ColumnRole.FIRST
        mapping.roleOf(header.indexOf("Northing")) shouldBe ColumnRole.SECOND
        mapping.roleOf(header.indexOf("Elevation")) shouldBe ColumnRole.HEIGHT
        mapping.roles.count { it != ColumnRole.IGNORE } shouldBe 5
        mapping.roles.size shouldBe header.size
        mapping.problem.shouldBeNull()
    }

    @Test
    fun `S028 RF-10 con coordenadas geograficas y altura elipsoidal, el CSV de S012 da la latitud, la longitud y esa altura`() {
        val header = s012Header("todas_coma_punto.csv")
        val mapping = suggest(header, ellipsoidal)

        mapping.roleOf(header.indexOf("Name")) shouldBe ColumnRole.NAME
        mapping.roleOf(header.indexOf("Latitude")) shouldBe ColumnRole.FIRST
        mapping.roleOf(header.indexOf("Longitude")) shouldBe ColumnRole.SECOND
        mapping.roleOf(header.indexOf("Ellipsoidal height")) shouldBe ColumnRole.HEIGHT
        mapping.roleOf(header.indexOf("Easting")) shouldBe ColumnRole.IGNORE
        mapping.roleOf(header.indexOf("Elevation")) shouldBe ColumnRole.IGNORE
    }

    @Test
    fun `S028 RF-10 con coordenadas geograficas y altura del proyecto, el CSV de S012 da la cota y no la elipsoidal`() {
        val header = s012Header("todas_coma_punto.csv")
        val mapping = suggest(header, geographicProjectHeight)

        mapping.roleOf(header.indexOf("Latitude")) shouldBe ColumnRole.FIRST
        mapping.roleOf(header.indexOf("Elevation")) shouldBe ColumnRole.HEIGHT
        mapping.roleOf(header.indexOf("Ellipsoidal height")) shouldBe ColumnRole.IGNORE
    }

    @Test
    fun `S028 RF-10 las demas cabeceras de S012 se reconocen igual`() {
        listOf("todas_puntoycoma_coma.csv", "todas_tabulador_punto.csv").forEach { name ->
            val header = s012Header(name)
            val mapping = suggest(header, project)

            mapping.roleOf(header.indexOf("Easting")) shouldBe ColumnRole.FIRST
            mapping.problem.shouldBeNull()
        }
    }

    @Test
    fun `S028 RF-10 el CSV PENZD de S012 se reconoce con su cabecera`() {
        val header = s012Header("penzd_puntoycoma_coma.csv")

        suggest(header, project).problem.shouldBeNull()
    }

    @Test
    fun `S028 RF-10 una cabecera de latitud y longitud con el SC del proyecto no da coordenadas y cae a PENZD`() {
        val header = listOf("Name", "Latitude", "Longitude", "Ellipsoidal height", "Description")

        suggest(header, project) shouldBe ColumnMapping.penzd(5)
        suggest(header, ellipsoidal) shouldBe ColumnMapping.penzd(5)
    }

    @Test
    fun `S028 RF-10 una cabecera de Este y Norte con coordenadas geograficas cae a PENZD`() {
        suggest(listOf("Name", "Easting", "Northing"), ellipsoidal) shouldBe ColumnMapping.penzd(3)
    }

    @Test
    fun `S028 RF-10 sin cabecera propone PENZD con el numero de columnas del fichero`() {
        suggest(null, columns = 5) shouldBe ColumnMapping.penzd(5)
        suggest(null, columns = 3) shouldBe ColumnMapping.penzd(3)
        suggest(null, columns = 7).roles.drop(5) shouldBe listOf(ColumnRole.IGNORE, ColumnRole.IGNORE)
        suggest(null, ellipsoidal, columns = 5) shouldBe ColumnMapping.penzd(5)
    }

    @Test
    fun `S028 RF-10 una cabecera que no se reconoce propone PENZD`() {
        suggest(listOf("a", "b", "c", "d")) shouldBe ColumnMapping.penzd(4)
        suggest(listOf("Col1", "Col2", "Col3")) shouldBe ColumnMapping.penzd(3)
    }

    @Test
    fun `S028 RF-10 solo reconocer el nombre no basta para fiarse de la cabecera`() {
        suggest(listOf("Name", "A", "B", "C")) shouldBe ColumnMapping.penzd(4)
    }

    @Test
    fun `S028 RF-11 un papel no se repite, se queda con la primera columna que lo cumple`() {
        val mapping = suggest(listOf("Name", "Easting", "Easting", "Northing", "Elevation"))

        mapping.roles shouldBe listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.IGNORE, ColumnRole.SECOND, ColumnRole.HEIGHT)
        mapping.problem.shouldBeNull()
    }

    @Test
    fun `S028 RF-11 dos columnas que valen para la altura, la primera es la altura`() {
        val mapping = suggest(listOf("Name", "Easting", "Northing", "Elevation", "Height"))

        mapping.roles shouldBe listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.HEIGHT, ColumnRole.IGNORE)
    }

    @Test
    fun `S028 RF-11 una cabecera con coordenadas pero sin nombre da una asignacion sin nombre, que no vale`() {
        val mapping = suggest(listOf("Easting", "Northing", "Elevation"))

        mapping.roles shouldBe listOf(ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.HEIGHT)
        mapping.problem shouldBe MappingProblem.MISSING_NAME
    }

    @Test
    fun `S028 RF-10 el numero de columnas del fichero manda sobre la longitud de la cabecera`() {
        suggest(listOf("Name", "Easting", "Northing"), columns = 5) shouldBe
            ColumnMapping(listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.IGNORE, ColumnRole.IGNORE))
        suggest(listOf("Name", "Easting", "Northing", "Elevation"), columns = 3) shouldBe ColumnMapping.penzd(3)
    }

    @Test
    fun `S028 RF-10 reconoce la latitud y la longitud con sus nombres en espanol y en ingles`() {
        suggest(listOf("Punto", "Latitud", "Longitud", "Altura"), geographicProjectHeight) shouldBe ColumnMapping.penzd(4)
        suggest(listOf("Name", "Lat", "Lon", "Ellipsoidal height"), ellipsoidal) shouldBe ColumnMapping.penzd(4)
        suggest(listOf("Name", "Lat", "Long", "Altura elipsoidal"), ellipsoidal) shouldBe ColumnMapping.penzd(4)
    }

    @Test
    fun `S028 RF-10 con altura elipsoidal y sin columna elipsoidal, se usa la altura que haya`() {
        suggest(listOf("Name", "Latitude", "Longitude", "Elevation"), ellipsoidal) shouldBe ColumnMapping.penzd(4)
    }

    @Test
    fun `S028 RF-10 con el SC del proyecto, una unica altura elipsoidal no se toma como altura`() {
        val mapping = suggest(listOf("Name", "Easting", "Northing", "Ellipsoidal height"))

        mapping.roles shouldBe listOf(ColumnRole.NAME, ColumnRole.FIRST, ColumnRole.SECOND, ColumnRole.IGNORE)
    }

    @Test
    fun `S028 RF-10 reconoce las cabeceras de cada fichero de ejemplo con cabecera`() {
        val geographic = "etrs89-geograficas.csv"
        val withHeader = ImportFixtures.samples.filter {
            it !in setOf("etrs89-utm30-tabulador-sin-cabecera.txt", "etrs89-utm30-espacios-pnezd.txt")
        }

        withHeader.forEach { name ->
            val bytes = ImportFixtures.bytes(name)
            val text = TextDecoding.decode(bytes, (TextDecoding.probe(bytes) as TextProbe.Text).encoding)
            val header = DelimitedReader.rows(StringReader(text), DelimitedReader.detectSeparator(text)).first().cells
            val coordinates = if (name == geographic) ellipsoidal else project

            suggest(header, coordinates) shouldBe ColumnMapping.penzd(5)
        }
    }
}

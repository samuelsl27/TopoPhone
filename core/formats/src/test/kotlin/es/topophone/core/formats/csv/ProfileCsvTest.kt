package es.topophone.core.formats.csv

import es.topophone.core.model.project.AngularUnit
import es.topophone.core.model.project.CorrectionsDatum
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.Project
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.survey.linear.LinearSurveyKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/**
 * El CSV de los perfiles tomados frente a `fixtures/ejes/toma/esperado-perfil-*.csv`, que escribe `tools/ejes/generar_toma_lineal.py` con
 * aritmética exacta (S052/RF-15, RF-22; constitución nº 2).
 */
class ProfileCsvTest {
    private val folder = File(
        generateSequence(File("").absoluteFile) { it.parentFile }.first {
            File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory
        },
        "fixtures/ejes/toma",
    )

    private fun project(unit: LinearUnit = LinearUnit.METRE) = Project(
        id = "p1",
        name = "Perfiles",
        author = "",
        description = "",
        crs = ProjectCrs.ETRS89_UTM30,
        linearUnit = unit,
        angularUnit = AngularUnit.DMS,
        correctionsDatum = CorrectionsDatum.ETRS89,
        folderId = null,
        createdAtMs = 0,
        modifiedAtMs = 0,
        openedAtMs = null,
        deletedAtMs = null,
    )

    private fun table(file: String) = File(folder, file).readLines().drop(1).map { it.split(",") }

    private fun number(text: String) = text.takeIf { it.isNotEmpty() }?.toDouble()

    /** Los puntos del longitudinal que entran en el perfil, con su cota de terreno de `longitudinal-puntos.csv`. */
    private fun longitudinalRows(): List<ProfileCsvRow> {
        val ground = table("longitudinal-puntos.csv").associate { it[0] to it[3].toDouble() }
        return table("esperado-longitudinal.csv").filter { it[5] == "ninguna" }.map {
            ProfileCsvRow(null, it[1].toDouble(), it[2].toDouble(), ground.getValue(it[0]), it[3].toDouble(), it[4].toDouble())
        }
    }

    /** Los puntos del transversal que entran en el perfil, por orden de desplazamiento, con su código. */
    private fun crossRows(): List<ProfileCsvRow> {
        val ground = table("transversal-puntos.csv").associate { it[0] to it[3].toDouble() }
        return table("esperado-transversal.csv").filter { it[5].isNotEmpty() }.sortedBy { it[5].toInt() }.map {
            val z = ground.getValue(it[0])
            val delta = number(it[3])
            ProfileCsvRow(it[6], SECTION_PK + it[1].toDouble(), it[2].toDouble(), z, delta?.let { d -> z - d }, delta)
        }
    }

    private fun write(kind: LinearSurveyKind, rows: List<ProfileCsvRow>, project: Project, sep: ColumnSeparator, dec: DecimalSeparator) =
        StringBuilder().also { ProfileCsv.write(kind, rows, project, sep, dec, it) }.toString()

    @Test
    fun `S052 RF-15 el longitudinal escrito es el fichero de tools byte a byte`() {
        val text = write(LinearSurveyKind.LONGITUDINAL, longitudinalRows(), project(), ColumnSeparator.COMMA, DecimalSeparator.POINT)

        text.toByteArray(Charsets.UTF_8).toList() shouldBe File(folder, "esperado-perfil-longitudinal.csv").readBytes().toList()
    }

    @Test
    fun `S052 RF-22 el transversal escrito es el fichero de tools byte a byte`() {
        val text = write(LinearSurveyKind.CROSS, crossRows(), project(), ColumnSeparator.COMMA, DecimalSeparator.POINT)

        text.toByteArray(Charsets.UTF_8).toList() shouldBe File(folder, "esperado-perfil-transversal.csv").readBytes().toList()
    }

    @Test
    fun `S052 RF-15 con punto y coma y coma decimal las cifras cambian de signo y las cabeceras no`() {
        val text =
            write(LinearSurveyKind.LONGITUDINAL, longitudinalRows().take(2), project(), ColumnSeparator.SEMICOLON, DecimalSeparator.COMMA)

        text.lines().take(3) shouldBe listOf(
            "Chainage;Offset;Ground elevation;Grade elevation;Delta elevation",
            "0,0000;0,0000;100,1000;100,0000;0,1000",
            "25,0000;0,2000;100,5000;100,5000;0,0000",
        )
    }

    @Test
    fun `S052 RF-15 las longitudes salen en la unidad del proyecto`() {
        val one = listOf(ProfileCsvRow(null, 100.0, 0.0, 1.0, null, null))

        val text =
            write(LinearSurveyKind.LONGITUDINAL, one, project(LinearUnit.INTERNATIONAL_FOOT), ColumnSeparator.COMMA, DecimalSeparator.POINT)

        text.lines()[1] shouldBe "328.0840,0.0000,3.2808,,"
    }

    @Test
    fun `S052 RF-22 un codigo con la coma va entre comillas`() {
        val one = listOf(ProfileCsvRow("A,B", 1.0, 2.0, 3.0, null, null))

        write(LinearSurveyKind.CROSS, one, project(), ColumnSeparator.COMMA, DecimalSeparator.POINT).lines()[1] shouldBe
            "\"A,B\",1.0000,2.0000,3.0000,,"
    }

    @Test
    fun `S052 RF-15 coma como separador y como signo decimal o un eje no se pueden exportar`() {
        shouldThrow<IllegalArgumentException> {
            write(LinearSurveyKind.LONGITUDINAL, emptyList(), project(), ColumnSeparator.COMMA, DecimalSeparator.COMMA)
        }
        shouldThrow<IllegalArgumentException> {
            write(LinearSurveyKind.AXIS, emptyList(), project(), ColumnSeparator.COMMA, DecimalSeparator.POINT)
        }
    }

    private companion object {
        const val SECTION_PK = 105.0
    }
}

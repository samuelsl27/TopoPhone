package es.topophone.core.survey

import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.Superelevation
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.alignment.section.TemplateSegment

/** Una fila de `fixtures/ejes/secciones/esperado-puntos.csv` (S050): la calcula `tools/ejes/generar_secciones.py` con fracciones. */
class ExpectedSectionPoint(
    val axis: String,
    val chainage: Double,
    val code: String,
    val offset: Double,
    val easting: Double,
    val northing: Double,
    val elevation: Double?,
    val slopePct: Double,
)

/** Una fila de `esperado-resolucion.csv`: `ok`, `faltan` (con los códigos) o `fuera`. */
class ExpectedResolution(val axis: String, val chainage: Double, val state: String, val missing: List<String>)

/** Los datos de `fixtures/ejes/secciones/`: las entradas (plantillas, peralte, CSV) y lo esperado (S050). */
object SectionFixtures {
    private fun rows(name: String) = SurveyFixtures.rows("secciones/$name", "ejes")

    fun template(axis: String): SectionTemplate? {
        val r = rows("plantillas.csv").filter { it[0] == axis }
        if (r.isEmpty()) return null
        fun side(name: String) = r.filter { it[4] == name }.sortedBy { it[5].toInt() }
            .map { TemplateSegment(it[6], it[7].toDouble(), it[8].toDouble(), it[9] == "1") }
        return SectionTemplate(r[0][1].toDouble(), r[0][2].toDouble(), r[0][3], side("izquierda"), side("derecha"))
    }

    fun superelevation(axis: String): Superelevation? =
        rows("peralte.csv").filter { it[0] == axis }.takeIf { it.isNotEmpty() }?.let { list ->
            Superelevation(list.map { SuperelevationRow(it[1].toDouble(), it[2].toDouble(), it[3].toDouble()) })
        }

    /** Las secciones de diseño explícitas del eje C, en cotas absolutas, tal como las trae su CSV. */
    fun explicit(kind: SectionKind = SectionKind.DESIGN): List<CrossSection> {
        val tag = if (kind == SectionKind.DESIGN) "diseno" else "terreno"
        return rows("secciones-eje-c.csv").filter { it[4] == tag }.groupBy { it[0].toDouble() }.map { (pk, list) ->
            CrossSection(
                kind,
                pk,
                HeightKind.ABSOLUTE,
                list.map {
                    SectionPoint(it[1], it[2].toDouble(), it[3].toDouble())
                }.sortedBy { it.offsetM },
            )
        }.sortedBy { it.chainage }
    }

    /** Diferencias de cota esperadas de una sección tipo: (eje, PK, código) a (desplazamiento, dz). */
    fun typeRows(): List<Triple<Pair<String, Double>, String, Pair<Double, Double>>> = rows("esperado-tipo.csv").map {
        Triple(it[0] to it[1].toDouble(), it[2], it[3].toDouble() to it[4].toDouble())
    }

    fun points(): List<ExpectedSectionPoint> = rows("esperado-puntos.csv").map {
        ExpectedSectionPoint(
            it[0],
            it[1].toDouble(),
            it[2],
            it[3].toDouble(),
            it[4].toDouble(),
            it[5].toDouble(),
            it[6].takeIf { v -> v.isNotEmpty() }?.toDouble(),
            it[7].toDouble(),
        )
    }

    fun resolutions(): List<ExpectedResolution> = rows("esperado-resolucion.csv").map {
        ExpectedResolution(it[0], it[1].toDouble(), it[2], it.getOrNull(3).orEmpty().split(";").filter(String::isNotEmpty))
    }

    /** (eje, PK, desplazamiento) a la cota esperada: null si "fuera de la sección", NaN si la sección no tiene cota. */
    fun offsets(): List<Pair<Triple<String, Double, Double>, Double?>> = rows("esperado-desplazamientos.csv").map {
        Triple(it[0], it[1].toDouble(), it[2].toDouble()) to when (it.getOrNull(3).orEmpty()) {
            "fuera" -> null
            "" -> Double.NaN
            else -> it[3].toDouble()
        }
    }
}

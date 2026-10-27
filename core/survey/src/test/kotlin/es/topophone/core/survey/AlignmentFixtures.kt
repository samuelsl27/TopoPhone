package es.topophone.core.survey

import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentExtent
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex

/** Un punto esperado de `fixtures/ejes/esperado-puntos.csv` (S049): lo calcula `tools/ejes` con otro algoritmo (constitución nº 2). */
class ExpectedAxisPoint(
    val axis: String,
    val chainage: Double,
    val offset: Double,
    val easting: Double,
    val northing: Double,
    val azimuthDeg: Double,
    val radius: Double,
    val elevation: Double?,
    val slopePct: Double?,
)

/** Los ejes de `fixtures/ejes/`, armados desde lo esperado y no desde los LandXML (S049). */
object AlignmentFixtures {
    private fun rows(name: String) = SurveyFixtures.rows(name, "ejes")

    private fun elementsOf(axis: String): List<AlignmentElement> = rows("esperado-tramos.csv").filter { it[0] == axis }.map {
        val kind = when (it[2]) {
            "line" -> ElementKind.LINE
            "arc" -> ElementKind.ARC
            else -> ElementKind.CLOTHOID
        }
        AlignmentElement(
            kind,
            it[3].toDouble(),
            it[4].toDouble(),
            it[7].toDouble(),
            it[8].toDouble(),
            Math.toRadians(it[11].toDouble()),
            it[5].toDouble(),
            it[6].toDouble(),
        )
    }

    fun profileOf(axis: String): VerticalProfile? = rows("esperado-rasantes.csv").filter {
        it[0] == axis
    }.takeIf { it.isNotEmpty() }?.let { list ->
        VerticalProfile(
            list.map {
                val curve = when (it[4]) {
                    "para" -> VerticalCurve.Parabola(it[5].toDouble())
                    "circ" -> VerticalCurve.Circular(it[6].toDouble(), it[5].toDouble())
                    else -> VerticalCurve.None
                }
                VerticalVertex(it[2].toDouble(), it[3].toDouble(), curve)
            },
        )
    }

    /** El final (Este, Norte) de cada tramo, como lo declara el fichero. */
    fun endsOf(axis: String): List<Pair<Double, Double>?> =
        rows("esperado-tramos.csv").filter { it[0] == axis }.map { it[9].toDouble() to it[10].toDouble() }

    fun alignment(axis: String, withProfile: Boolean = true) = Alignment(
        id = axis,
        projectId = "p",
        name = axis,
        fileName = "ejes.xml",
        importedAtMs = 0L,
        source = AlignmentSource.LANDXML,
        elements = elementsOf(axis),
        profile = if (withProfile) profileOf(axis) else null,
        visible = true,
        extent = AlignmentExtent(0.0, 0.0, 0.0, 0.0),
    )

    val axes = listOf("Eje A", "Eje B", "Eje C")

    fun points(): List<ExpectedAxisPoint> = rows("esperado-puntos.csv").map {
        ExpectedAxisPoint(
            it[0], it[1].toDouble(), it[2].toDouble(), it[3].toDouble(), it[4].toDouble(), it[5].toDouble(),
            if (it[6] == "inf") Double.POSITIVE_INFINITY else it[6].toDouble(),
            it.getOrNull(7)?.takeIf { v -> v.isNotEmpty() }?.toDouble(),
            it.getOrNull(8)?.takeIf { v -> v.isNotEmpty() }?.toDouble(),
        )
    }
}

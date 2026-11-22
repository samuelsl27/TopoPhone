package es.topophone.core.survey

import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.StepSpec
import es.topophone.core.model.point.GeodeticPosition
import es.topophone.core.model.project.GeodeticDatum
import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.SpainCrs

/** Un punto del proyecto tal como lo necesita el cambio de SC: su nombre y su posición del suelo, que no cambia (S035/RF-18). */
data class PlanPoint(val id: String, val name: String, val ground: GeodeticPosition)

/** Un punto que el SC nuevo no puede calcular, con el motivo (S035/RF-19). */
data class CrsChangeIssue(val id: String, val name: String, val unavailable: Set<PositionUnavailable>, val missingGrid: String? = null)

/**
 * Lo que pasaría con los puntos al cambiar el SC del proyecto: cuántos se miraron y cuáles no se pueden calcular en el SC nuevo (S035/RF-19). Con
 * [issues] vacío el cambio se aplica sin preguntar.
 */
data class CrsChangePlan(val total: Int, val issues: List<CrsChangeIssue>) {
    val isClean: Boolean get() = issues.isEmpty()

    /** Las rejillas que faltan para calcular algún punto, sin repetir. */
    val missingGrids: Set<String> get() = issues.mapNotNull { it.missingGrid }.toSet()
}

/** El cambio del SC de un proyecto con puntos (S035/RF-17 a RF-19). */
object CrsChange {
    /** Lo que impide calcular un punto en un SC: el resto (sin altura, fuera del geoide) es de la altura, no del SC. */
    private val horizontal = setOf(
        PositionUnavailable.OUTSIDE_CRS_AREA,
        PositionUnavailable.OUTSIDE_ED50_GRID,
        PositionUnavailable.OUTSIDE_DATUM_GRID,
        PositionUnavailable.MISSING_GRID,
    )

    /** Calcula cada punto, de su posición del suelo, en [target] y dice cuáles no se pueden calcular. */
    fun plan(
        points: List<PlanPoint>,
        target: ProjectCrs,
        heightSystem: HeightSystem,
        calculator: ProjectPositionCalculator = ProjectPositionCalculator(),
    ): CrsChangePlan {
        val issues = points.mapNotNull { point ->
            val position = calculator.fromGround(point.ground, target, heightSystem)
            val reasons = position.unavailable.intersect(horizontal)
            val computed = position.easting != null || position.latitudeDeg != null
            if (reasons.isEmpty() && computed) null else CrsChangeIssue(point.id, point.name, reasons, position.missingGrid)
        }
        return CrsChangePlan(points.size, issues)
    }

    /**
     * ¿Son [a] y [b] del mismo datum? Solo se cambia a un SC del mismo datum (S035/RF-17): el mismo elipsoide, el mismo meridiano de origen y la
     * misma transformación hasta las correcciones. ETRS89 y REGCAN95 son uno (GRS 1980, sin transformación) y ED50, el de la rejilla del IGN.
     */
    fun sameDatum(a: ProjectCrs, b: ProjectCrs): Boolean = datumKey(a) == datumKey(b)

    private fun datumKey(crs: ProjectCrs): String = when (crs) {
        is SpainCrs -> if (crs.datum == GeodeticDatum.ED50) ED50 else GRS80_NO_SHIFT
        is LibraryCrs -> {
            val d = crs.definition
            val steps = steps(d.transformationSteps)
            when {
                d.datumCode == ED50_CODE || "SPED2ETV2" in steps -> ED50
                else -> "${d.ellipsoidSemiMajorM}|${d.ellipsoidInverseFlattening}|${d.primeMeridianDeg}|$steps"
            }
        }
    }

    /** Las transformaciones sin cambio de datum, de los elipsoides GRS 1980, son todas la misma: las de España y las de la biblioteca. */
    private fun steps(text: String): String {
        val parsed = try {
            CrsCatalog.parseSteps(text)
        } catch (_: RuntimeException) {
            return text
        }
        return if (parsed.all { it == StepSpec.Null }) "null" else text
    }

    private const val ED50 = "ED50"
    private const val ED50_CODE = 6230 // el datum «European Datum 1950» de EPSG
    private val GRS80_NO_SHIFT = "6378137.0|298.257222101|0.0|null"
}

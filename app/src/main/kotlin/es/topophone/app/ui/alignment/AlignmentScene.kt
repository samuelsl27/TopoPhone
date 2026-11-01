package es.topophone.app.ui.alignment

import es.topophone.core.model.alignment.Alignment
import es.topophone.core.model.alignment.ChainageFormat
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.survey.AlignmentGeometry
import es.topophone.core.survey.AlignmentSampler
import es.topophone.core.survey.CadPlacement
import es.topophone.core.survey.ChainageMarks
import es.topophone.core.survey.SectionResolution
import es.topophone.core.survey.SectionResolver
import java.util.Locale
import kotlin.math.max

/** Un eje listo para el mapa: longitud y latitud de cada vértice, una tras otra; [active] es el eje activo, que se dibuja destacado (S049/RF-17). */
class AlignmentMapLine(val lonLat: DoubleArray, val active: Boolean) {
    val vertexCount: Int get() = lonLat.size / 2
}

/** Una marca de PK con su rótulo (`1+200`) sobre el eje (S049/RF-17). */
data class AlignmentMapMark(val latitudeDeg: Double, val longitudeDeg: Double, val label: String, val active: Boolean)

/** La línea de un código de sección a lo largo de un eje, con el código para rotularla (S050/RF-14). */
class AlignmentSectionLine(val code: String, val lonLat: DoubleArray) {
    val vertexCount: Int get() = lonLat.size / 2
}

/** Lo que el mapa dibuja de los ejes visibles del proyecto en la vista de ahora (S049/RF-17) y, donde se pidió, sus líneas de sección (S050/RF-14). */
class AlignmentScene(
    val lines: List<AlignmentMapLine>,
    val marks: List<AlignmentMapMark>,
    val sectionLines: List<AlignmentSectionLine> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty() && marks.isEmpty() && sectionLines.isEmpty()
}

/**
 * De los ejes visibles a lo que se dibuja (S049/RF-17, plan A8): cada eje que corta la vista se muestrea con una flecha de medio píxel del
 * mapa (nunca menos de 1 cm) y las marcas de PK salen a un intervalo redondo que depende de la escala, solo las que caen en la vista. El
 * PK se rotula en la unidad del proyecto (RNF-T9). El trabajo es de un hilo de fondo (RNF-T13): el mapa solo recibe la escena hecha.
 */
object AlignmentSceneBuilder {
    private const val MIN_SAGITTA_M = 0.01
    private const val MAX_STEP_M = 100.0
    private const val MAX_AXIS_MARKS = 20_000
    private const val MAX_VISIBLE_MARKS = 400
    private const val MAX_SECTION_SAMPLES = 50_000

    /**
     * La escena de los ejes visibles de [alignments] para [crs], con [metresPerPixel] metros por píxel. [viewport] es la vista en el
     * sistema del proyecto (null: todo); null si no hay nada que dibujar o el proyecto no tiene plano (geográficas).
     */
    fun build(
        alignments: List<Alignment>,
        activeId: String?,
        crs: ProjectCrs,
        unit: LinearUnit,
        locale: Locale,
        metresPerPixel: Double,
        viewport: CadExtent?,
        sections: Map<String, SectionResolver> = emptyMap(),
    ): AlignmentScene? {
        if (crs.utmZone == null) return null
        val lines = ArrayList<AlignmentMapLine>()
        val marks = ArrayList<AlignmentMapMark>()
        val sectionLines = ArrayList<AlignmentSectionLine>()
        for (alignment in alignments) {
            if (!alignment.visible || !overlaps(alignment, viewport)) continue
            val active = alignment.id == activeId
            line(alignment, crs, metresPerPixel, active)?.let { lines += it }
            marks += marksOf(alignment, crs, unit, locale, metresPerPixel, viewport, active, MAX_VISIBLE_MARKS - marks.size)
            sections[alignment.id]?.let { sectionLines += sectionLinesOf(alignment, it, crs, metresPerPixel) }
        }
        return AlignmentScene(lines, marks, sectionLines).takeUnless { it.isEmpty }
    }

    /**
     * Una línea por código de sección sobre el eje (S050/RF-14): se muestrean los PK como el eje, más los PK donde cambia la sección o
     * el peralte, y en cada uno se evalúa la sección del PK. Un código que no está en una sección del tramo corta su línea.
     */
    private fun sectionLinesOf(
        alignment: Alignment,
        resolver: SectionResolver,
        crs: ProjectCrs,
        metresPerPixel: Double,
    ): List<AlignmentSectionLine> {
        val geometry = AlignmentGeometry(alignment)
        val samples = AlignmentSampler.sample(alignment.elements, max(MIN_SAGITTA_M, metresPerPixel / 2), MAX_STEP_M)
        val keys = resolver.keyStations(alignment.startChainage, alignment.endChainage).sorted()
        // El punto medio de cada tramo entre dos PK singulares: ahí se ve si las dos secciones que lo rodean se pueden interpolar.
        val middles = keys.zipWithNext { a, b -> (a + b) / 2 }
        val chainages = (samples.chainage.toList() + keys + middles).sorted().distinct()
        val open = LinkedHashMap<String, MutableList<Double>>()
        val done = ArrayList<AlignmentSectionLine>()

        fun flush(code: String) {
            val coordinates = open.remove(code) ?: return
            if (coordinates.size >= 4) done += AlignmentSectionLine(code, coordinates.toDoubleArray())
        }
        var count = 0
        for (pk in chainages) {
            if (count++ > MAX_SECTION_SAMPLES) break
            val section = (resolver.at(pk) as? SectionResolution.Found)?.section
            if (section == null) {
                open.keys.toList().forEach(::flush)
                continue
            }
            val present = section.points.map { it.code }.toSet()
            open.keys.filter { it !in present }.forEach(::flush)
            for (p in section.points) {
                val point = geometry.pointAt(pk, p.offsetM) ?: continue
                val (lat, lon) = CadPlacement.geographic(crs, point.easting, point.northing) ?: continue
                open.getOrPut(p.code) { ArrayList() }.apply {
                    add(lon)
                    add(lat)
                }
            }
        }
        open.keys.toList().forEach(::flush)
        return done
    }

    private fun overlaps(alignment: Alignment, viewport: CadExtent?): Boolean {
        val e = alignment.extent
        return viewport == null ||
            (
                e.maxEasting >= viewport.minEasting &&
                    e.minEasting <= viewport.maxEasting &&
                    e.maxNorthing >= viewport.minNorthing &&
                    e.minNorthing <= viewport.maxNorthing
                )
    }

    private fun line(alignment: Alignment, crs: ProjectCrs, metresPerPixel: Double, active: Boolean): AlignmentMapLine? {
        val samples = AlignmentSampler.sample(alignment.elements, max(MIN_SAGITTA_M, metresPerPixel / 2), MAX_STEP_M)
        val lonLat = DoubleArray(samples.size * 2)
        for (i in 0 until samples.size) {
            val (lat, lon) = CadPlacement.geographic(crs, samples.easting[i], samples.northing[i]) ?: return null
            lonLat[i * 2] = lon
            lonLat[i * 2 + 1] = lat
        }
        return AlignmentMapLine(lonLat, active)
    }

    private fun marksOf(
        alignment: Alignment,
        crs: ProjectCrs,
        unit: LinearUnit,
        locale: Locale,
        metresPerPixel: Double,
        viewport: CadExtent?,
        active: Boolean,
        room: Int,
    ): List<AlignmentMapMark> {
        if (room <= 0) return emptyList()
        // Los múltiplos del intervalo se toman en la unidad del proyecto, así el rótulo de un proyecto en pies sale en estaciones redondas.
        val interval = ChainageMarks.interval(metresPerPixel / unit.metres)
        val decimals = if (interval >= 1.0) {
            0
        } else if (interval >= 0.1) {
            1
        } else {
            2
        }
        val geometry = AlignmentGeometry(alignment)
        val chainages = ChainageMarks.chainages(
            unit.fromMetres(alignment.startChainage),
            unit.fromMetres(alignment.endChainage),
            interval,
            MAX_AXIS_MARKS,
        )
        val out = ArrayList<AlignmentMapMark>()
        for (value in chainages) {
            if (out.size >= room) break
            val chainage = unit.toMetres(value)
            val point = geometry.pointAt(chainage) ?: continue
            if (viewport != null &&
                !(point.easting in viewport.minEasting..viewport.maxEasting && point.northing in viewport.minNorthing..viewport.maxNorthing)
            ) {
                continue
            }
            val (lat, lon) = CadPlacement.geographic(crs, point.easting, point.northing) ?: continue
            out += AlignmentMapMark(lat, lon, ChainageFormat.format(chainage, unit, locale, decimals), active)
        }
        return out
    }
}

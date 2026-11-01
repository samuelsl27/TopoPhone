package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlStreamReader
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionPoint
import es.topophone.core.model.project.LinearUnit
import java.io.InputStream

/**
 * Lo que se sabe de una sección del fichero sin leer sus puntos (S050/RF-5). [index] es su posición entre todas las secciones
 * (`CrossSectSurf` o `DesignCrossSectSurf`) y sirve para pedirla a [LandXmlCrossSectReader.read]. El PK está en la unidad del fichero.
 */
class LandXmlCrossSectInfo(
    val index: Int,
    val station: Double,
    val name: String,
    val kind: SectionKind,
    val pointCount: Int,
    /** Cuántos puntos no traen código: se llamarán P1, P2… (RF-5). */
    val unnamedCount: Int,
)

/** Lo que dice un LandXML sobre sus secciones: versión, unidad, eje al que dice pertenecer y secciones; [skipped] son las no legibles. */
class LandXmlCrossSectScan(
    val version: String?,
    val declaredUnit: LinearUnit?,
    val alignmentRef: String?,
    val sections: List<LandXmlCrossSectInfo>,
    val skipped: Int,
)

/**
 * Lee las secciones transversales de un LandXML por flujo (S050/RF-5, plan A6, decisión D8), con el mismo `XmlStreamReader` y las
 * mismas comprobaciones de versión y de unidad que las alineaciones (S049). Cada `CrossSectPnt` es "desplazamiento cota", la cota
 * absoluta, en la unidad del fichero. Una sección es de diseño si es una `DesignCrossSectSurf` o una `CrossSectSurf` que no es
 * `existing`; las `existing` son perfiles de terreno. No hay ningún LandXML real con secciones con el que probarlo (D8).
 */
object LandXmlCrossSectReader {
    /** El mismo límite que las superficies y los ejes: 100 MB. */
    const val MAX_BYTES = LandXmlAlignmentReader.MAX_BYTES

    private val WHITESPACE = Regex("\\s+")

    /** Las secciones del fichero con sus recuentos. [LandXmlException] si no es un LandXML 1.0 a 1.2; sin `CrossSects`, lista vacía. */
    fun scan(input: InputStream): LandXmlCrossSectScan = walk(input, null).first

    /** Las secciones de [indices] (las [LandXmlCrossSectInfo.index] de [scan]) en el orden del fichero, en la unidad del fichero. */
    fun read(input: InputStream, indices: Set<Int>): List<CrossSection> =
        if (indices.isEmpty()) emptyList() else walk(input, indices).second

    /** Pasa las secciones de la unidad del fichero (la que el usuario confirma) a metros (RNF-T9). */
    fun toMetres(sections: List<CrossSection>, unit: LinearUnit): List<CrossSection> = sections.map { s ->
        s.copy(
            chainage = unit.toMetres(s.chainage),
            points = s.points.map {
                it.copy(offsetM = unit.toMetres(it.offsetM), heightM = unit.toMetres(it.heightM))
            },
        )
    }

    private class Surface(val station: Double, val name: String, val kind: SectionKind, val points: List<Pair<String?, DoubleArray>>)

    private fun walk(input: InputStream, wanted: Set<Int>?): Pair<LandXmlCrossSectScan, List<CrossSection>> {
        val xml = XmlStreamReader(input)
        val header = LandXmlHeader()
        val infos = mutableListOf<LandXmlCrossSectInfo>()
        val sections = mutableListOf<CrossSection>()
        var alignmentRef: String? = null
        var skipped = 0
        var next = 0
        LandXmlHeader.walk(xml, header, "CrossSects", "CrossSect", onContainer = {
            alignmentRef = xml.attribute("alignmentRef")
        }) { _, name ->
            val station = xml.attribute("sta")?.trim()?.toDoubleOrNull()
            val surfaces = readSurfaces(xml, name, station)
            if (station == null) skipped++
            for (s in surfaces.first) {
                val index = next++
                if (wanted == null) {
                    infos += LandXmlCrossSectInfo(index, s.station, s.name, s.kind, s.points.size, s.points.count { it.first == null })
                } else if (index in wanted) {
                    sections += toSection(s)
                }
            }
            skipped += surfaces.second
            wanted != null && next > (wanted.maxOrNull() ?: -1) && sections.size == wanted.size
        }
        return LandXmlCrossSectScan(header.version, header.unit, alignmentRef, infos, skipped) to sections
    }

    /** Las superficies de la `CrossSect` recién abierta y cuántas no se pudieron leer; deja el lector tras su `END`. */
    private fun readSurfaces(xml: XmlStreamReader, sectionName: String, station: Double?): Pair<List<Surface>, Int> {
        val base = xml.depth
        val surfaces = mutableListOf<Surface>()
        var skipped = 0
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> when (xml.name) {
                    "CrossSectSurf", "DesignCrossSectSurf" -> {
                        val kind = if (xml.name == "DesignCrossSectSurf" || xml.attribute("state")?.lowercase() != "existing") {
                            SectionKind.DESIGN
                        } else {
                            SectionKind.GROUND
                        }
                        val points = readPoints(xml)
                        if (station == null) {
                            // La sección entera se omite: cuenta una vez, no por cada superficie.
                        } else if (points == null) {
                            skipped++
                        } else if (points.isNotEmpty()) {
                            surfaces += Surface(station, sectionName, kind, points)
                        }
                    }

                    else -> xml.skipElement()
                }

                XmlStreamReader.Event.END -> if (xml.depth < base) break
                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }
        return surfaces to skipped
    }

    /** Los puntos (código o null, [desplazamiento, cota]) de la superficie recién abierta; null si alguno no es legible. */
    private fun readPoints(xml: XmlStreamReader): List<Pair<String?, DoubleArray>>? {
        val base = xml.depth
        val points = mutableListOf<Pair<String?, DoubleArray>>()
        var bad = false
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> if (xml.name == "CrossSectPnt") {
                    val code = (xml.attribute("code") ?: xml.attribute("name"))?.trim()?.takeIf { it.isNotEmpty() }
                    var numbers = DoubleArray(0)
                    if (xml.next() == XmlStreamReader.Event.TEXT) {
                        numbers = xml.text.toString().trim().split(WHITESPACE).mapNotNull { it.toDoubleOrNull() }.toDoubleArray()
                        if (xml.next() == XmlStreamReader.Event.START) xml.skipElement()
                    }
                    if (numbers.size >= 2) points += code to doubleArrayOf(numbers[0], numbers[1]) else bad = true
                } else {
                    xml.skipElement()
                }

                XmlStreamReader.Event.END -> if (xml.depth < base) break
                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }
        return if (bad) null else points
    }

    private fun toSection(s: Surface): CrossSection {
        val sorted = s.points.sortedBy { it.second[0] }
        val points = sorted.mapIndexed { i, (code, v) -> SectionPoint(code ?: SectionCode.generated(i), v[0], v[1]) }
        return CrossSection(s.kind, s.station, HeightKind.ABSOLUTE, points)
    }
}

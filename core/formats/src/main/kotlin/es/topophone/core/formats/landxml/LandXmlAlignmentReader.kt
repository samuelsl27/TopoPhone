package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlStreamReader
import es.topophone.core.model.alignment.AlignmentElement
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.ElementKind
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.alignment.VerticalCurve
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.project.LinearUnit
import java.io.InputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Lo que se sabe de una alineación del fichero sin leer sus tramos (S049/RF-2). [index] es su posición entre todas las alineaciones y
 * sirve para pedirla a [LandXmlAlignmentReader.read]. La longitud y el PK inicial están en la unidad del fichero.
 */
class LandXmlAlignmentInfo(
    val index: Int,
    val name: String,
    val length: Double,
    val startStation: Double,
    val elementCount: Int,
    /** Cuántas rasantes (`ProfAlign`) con al menos dos vértices trae. */
    val profileCount: Int,
    val hasStationEquation: Boolean,
) {
    /** Trae rasante (RF-2). */
    val hasProfile: Boolean get() = profileCount > 0
}

/** Lo que dice un LandXML sobre sus alineaciones sin leer sus tramos: su versión, su unidad y sus alineaciones (S049/RF-2, RF-3). */
class LandXmlAlignmentScan(val version: String?, val declaredUnit: LinearUnit?, val alignments: List<LandXmlAlignmentInfo>)

/**
 * Una alineación leída, en la unidad del fichero y sin convertir de sistema (S049/RF-4, RF-5): sus tramos con el PK de cada uno
 * (desde [startStation]), el final que declara cada tramo como (Este, Norte) (para comprobarlo contra su geometría), la rasante con sus
 * vértices y, si no se puede usar, por qué ([profileOmission], decisión D3). [issue] es el motivo por el que la alineación no se
 * importa (un tipo de tramo no admitido o ecuaciones de PK), con los tramos leídos hasta ahí.
 */
class LandXmlAlignmentRead(
    val name: String,
    val startStation: Double,
    val elements: List<AlignmentElement>,
    val declaredEnds: List<Pair<Double, Double>?>,
    val profile: List<VerticalVertex>?,
    val profileOmission: ProfileOmission?,
    val issue: AlignmentIssue?,
)

/**
 * Lee las alineaciones de un LandXML por flujo (S049/RF-2 a RF-5, plan A2), con el mismo `XmlStreamReader` y las mismas comprobaciones de
 * versión y de unidad que las superficies (S042). [scan] lista las alineaciones; [read] entrega las elegidas. Las coordenadas de
 * LandXML van **Norte, Este** y los ángulos en grados decimales medidos desde el Norte en sentido horario.
 */
object LandXmlAlignmentReader {
    /** El mismo límite que las superficies y los dibujos: 100 MB (RF-3). */
    const val MAX_BYTES = 100L * 1024 * 1024

    private val PROFILE_VERTICES = setOf("PVI", "ParaCurve", "CircCurve", "UnsymParaCurve")
    private val ATTRIBUTES =
        listOf("length", "dir", "dirStart", "dirEnd", "rot", "radius", "radiusStart", "radiusEnd", "crvType", "spiType")
    private val POINTS = setOf("Start", "End", "Center", "PI")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Las alineaciones del fichero con sus recuentos. [es.topophone.core.formats.xml.XmlFormatException] si el XML está mal;
     * [LandXmlException] si no es un LandXML 1.0 a 1.2. Un fichero sin alineaciones da una lista vacía (RF-3).
     */
    fun scan(input: InputStream): LandXmlAlignmentScan {
        val xml = XmlStreamReader(input)
        val header = LandXmlHeader()
        val alignments = mutableListOf<LandXmlAlignmentInfo>()
        LandXmlHeader.walk(xml, header, "Alignments", "Alignment") { index, name ->
            alignments += scanAlignment(xml, index, name)
            false
        }
        return LandXmlAlignmentScan(header.version, header.unit, alignments)
    }

    /**
     * Las alineaciones de [indices] (las [LandXmlAlignmentInfo.index] de [scan]) en el orden del fichero. Los tramos se arman con lo que
     * el fichero declara, sin integrar nada: el azimut de inicio de cada uno, su longitud y sus curvaturas (RF-4). Un tipo de tramo que
     * no es recta, arco ni clotoide (`spiType` distinto de `clothoid` incluido) deja la alineación con su [LandXmlAlignmentRead.issue] (RF-5).
     */
    fun read(input: InputStream, indices: Set<Int>): List<LandXmlAlignmentRead> {
        val xml = XmlStreamReader(input)
        val result = mutableListOf<LandXmlAlignmentRead>()
        if (indices.isEmpty()) return result
        LandXmlHeader.walk(xml, LandXmlHeader(), "Alignments", "Alignment") { index, name ->
            if (index in indices) result += readAlignment(xml, name) else xml.skipElement()
            result.size == indices.size
        }
        return result
    }

    private fun scanAlignment(xml: XmlStreamReader, index: Int, name: String): LandXmlAlignmentInfo {
        val declaredLength = xml.attribute("length")?.trim()?.toDoubleOrNull()
        val start = xml.attribute("staStart")?.trim()?.toDoubleOrNull() ?: 0.0
        val base = xml.depth
        var elements = 0
        var profiles = 0
        var equation = false
        var inGeometry = false
        var inProfile = false
        var inProfAlign = false
        var profileVertices = 0
        var summedLength = 0.0
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> when (xml.depth - base) {
                    1 -> when (xml.name) {
                        "CoordGeom" -> inGeometry = true
                        "Profile" -> inProfile = true
                        "StaEquation" -> {
                            equation = true
                            xml.skipElement()
                        }
                        else -> xml.skipElement()
                    }

                    2 -> when {
                        inGeometry -> {
                            elements++
                            summedLength += xml.attribute("length")?.trim()?.toDoubleOrNull() ?: 0.0
                            xml.skipElement()
                        }

                        inProfile && xml.name == "ProfAlign" -> {
                            inProfAlign = true
                            profileVertices = 0
                        }

                        else -> xml.skipElement()
                    }

                    3 -> {
                        if (inProfAlign && xml.name in PROFILE_VERTICES) profileVertices++
                        xml.skipElement()
                    }

                    else -> xml.skipElement()
                }

                XmlStreamReader.Event.END -> when {
                    xml.name == "Alignment" && xml.depth == base - 1 -> break
                    xml.name == "CoordGeom" && xml.depth == base -> inGeometry = false
                    xml.name == "Profile" && xml.depth == base -> inProfile = false
                    xml.name == "ProfAlign" && xml.depth == base + 1 -> {
                        if (inProfAlign && profileVertices >= 2) profiles++
                        inProfAlign = false
                    }
                }

                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }
        return LandXmlAlignmentInfo(index, name, declaredLength ?: summedLength, start, elements, profiles, equation)
    }

    private class Geometry(val name: String, val attributes: Map<String, String?>, val points: Map<String, Pair<Double, Double>>)

    private class ProfileVertex(val name: String, val chainage: Double, val elevation: Double, val length: Double?, val radius: Double?)

    private fun readAlignment(xml: XmlStreamReader, name: String): LandXmlAlignmentRead {
        val start = xml.attribute("staStart")?.trim()?.toDoubleOrNull() ?: 0.0
        val base = xml.depth
        val geometry = mutableListOf<Geometry>()
        val profiles = mutableListOf<List<ProfileVertex>>()
        var equation = false
        var inGeometry = false
        var inProfile = false
        var current: MutableList<ProfileVertex>? = null
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> when (xml.depth - base) {
                    1 -> when (xml.name) {
                        "CoordGeom" -> inGeometry = true
                        "Profile" -> inProfile = true
                        "StaEquation" -> {
                            equation = true
                            xml.skipElement()
                        }
                        else -> xml.skipElement()
                    }

                    2 -> when {
                        inGeometry -> geometry += readGeometry(xml)
                        inProfile && xml.name == "ProfAlign" -> current = mutableListOf()
                        else -> xml.skipElement()
                    }

                    3 -> {
                        val vertices = current
                        if (vertices != null && xml.name in PROFILE_VERTICES) {
                            readProfileVertex(xml)?.let { vertices.add(it) }
                        } else {
                            xml.skipElement()
                        }
                    }

                    else -> xml.skipElement()
                }

                XmlStreamReader.Event.END -> when {
                    xml.name == "Alignment" && xml.depth == base - 1 -> break
                    xml.name == "CoordGeom" && xml.depth == base -> inGeometry = false
                    xml.name == "Profile" && xml.depth == base -> inProfile = false
                    xml.name == "ProfAlign" && xml.depth == base + 1 -> {
                        current?.let { if (it.size >= 2) profiles += it }
                        current = null
                    }
                }

                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }
        val plan = buildElements(geometry, start)
        val (profile, omission) = buildProfile(profiles)
        return LandXmlAlignmentRead(
            name = name,
            startStation = start,
            elements = plan.elements,
            declaredEnds = plan.ends,
            profile = profile,
            profileOmission = omission,
            issue = if (equation) AlignmentIssue.StationEquation else plan.issue,
        )
    }

    /** Un elemento de `CoordGeom` recién abierto, con sus atributos y sus puntos (Norte, Este), dejando el lector tras su `END`. */
    private fun readGeometry(xml: XmlStreamReader): Geometry {
        val name = xml.name
        val attributes = ATTRIBUTES.associateWith { xml.attribute(it) }
        val points = HashMap<String, Pair<Double, Double>>()
        val inside = xml.depth
        while (true) {
            when (xml.next()) {
                XmlStreamReader.Event.START -> {
                    val point = xml.name
                    if (point !in POINTS) {
                        xml.skipElement()
                    } else if (xml.next() == XmlStreamReader.Event.TEXT) {
                        val numbers = xml.text.toString().trim().split(WHITESPACE).mapNotNull { it.toDoubleOrNull() }
                        if (numbers.size >= 2) points[point] = numbers[0] to numbers[1]
                    }
                }

                XmlStreamReader.Event.END -> if (xml.depth == inside - 1) break
                XmlStreamReader.Event.EOF -> break
                else -> Unit
            }
        }
        return Geometry(name, attributes, points)
    }

    /** Un vértice de la rasante recién abierto, dejando el lector tras su `END`; null si su texto no son dos números. */
    private fun readProfileVertex(xml: XmlStreamReader): ProfileVertex? {
        val name = xml.name
        val length = xml.attribute("length")?.trim()?.toDoubleOrNull()
        val radius = xml.attribute("radius")?.trim()?.toDoubleOrNull()
        var vertex: ProfileVertex? = null
        var event = xml.next()
        if (event == XmlStreamReader.Event.TEXT) {
            val numbers = xml.text.toString().trim().split(WHITESPACE).mapNotNull { it.toDoubleOrNull() }
            if (numbers.size >= 2) vertex = ProfileVertex(name, numbers[0], numbers[1], length, radius)
            event = xml.next()
        }
        if (event == XmlStreamReader.Event.START) xml.skipElement()
        return vertex
    }

    private class Plan(val elements: List<AlignmentElement>, val ends: List<Pair<Double, Double>?>, val issue: AlignmentIssue?)

    private fun buildElements(geometry: List<Geometry>, startStation: Double): Plan {
        val elements = mutableListOf<AlignmentElement>()
        val ends = mutableListOf<Pair<Double, Double>?>()
        var chainage = startStation
        var previousEnd: Double? = null
        for ((i, g) in geometry.withIndex()) {
            val begin = g.points["Start"]
            val element = when (g.name) {
                "Line" -> line(g, begin, chainage)
                "Curve" -> curve(g, begin, chainage)
                "Spiral" -> spiral(g, begin, chainage, previousEnd)
                else -> return Plan(elements, ends, AlignmentIssue.UnsupportedElement(i + 1, g.name))
            }
            if (element == null) return Plan(elements, ends, AlignmentIssue.UnsupportedElement(i + 1, unsupportedName(g)))
            elements += element
            ends += g.points["End"]?.let { it.second to it.first }
            chainage += element.length
            previousEnd = g.attributes["dirEnd"]?.trim()?.toDoubleOrNull()?.let { Math.toRadians(it) }
                ?: if (element.kind == ElementKind.LINE) element.startAzimuth else null
        }
        return Plan(elements, ends, null)
    }

    /** Cómo llama el fichero a lo que no se admite: el tipo de espiral o de curva si lo dice, y si no la etiqueta. */
    private fun unsupportedName(g: Geometry): String = when (g.name) {
        "Spiral" -> g.attributes["spiType"]?.takeIf { !it.equals("clothoid", ignoreCase = true) } ?: g.name
        "Curve" -> g.attributes["crvType"]?.takeIf { !it.equals("arc", ignoreCase = true) } ?: g.name
        else -> g.name
    }

    /** El azimut en [0, 2π). */
    private fun positive(azimuth: Double): Double = if (azimuth < 0.0) azimuth + 2 * PI else azimuth

    private fun number(g: Geometry, key: String): Double? = g.attributes[key]?.trim()?.toDoubleOrNull()

    private fun line(g: Geometry, begin: Pair<Double, Double>?, chainage: Double): AlignmentElement? {
        begin ?: return null
        val end = g.points["End"]
        val azimuth = number(g, "dir")?.let { Math.toRadians(it) }
            ?: end?.let { positive(atan2(it.second - begin.second, it.first - begin.first)) }
            ?: return null
        val length = number(g, "length") ?: end?.let { hypot(it.first - begin.first, it.second - begin.second) } ?: return null
        if (!(length > 0.0)) return null
        // begin es (Norte, Este)
        return AlignmentElement.line(chainage, length, begin.second, begin.first, azimuth)
    }

    private fun sign(g: Geometry): Double? = when (g.attributes["rot"]?.trim()?.lowercase()) {
        "ccw" -> 1.0
        "cw" -> -1.0
        else -> null
    }

    private fun curve(g: Geometry, begin: Pair<Double, Double>?, chainage: Double): AlignmentElement? {
        begin ?: return null
        val type = g.attributes["crvType"]
        if (type != null && !type.equals("arc", ignoreCase = true)) return null
        val sign = sign(g) ?: return null
        val center = g.points["Center"] ?: return null
        val radius = number(g, "radius") ?: hypot(begin.first - center.first, begin.second - center.second)
        if (!(radius > 0.0)) return null
        // v va del centro al inicio, en (Este, Norte); la tangente es v girado +90° (antihorario) o -90° (horario).
        val vE = begin.second - center.second
        val vN = begin.first - center.first
        val azimuth = positive(if (sign > 0) atan2(-vN, vE) else atan2(vN, -vE))
        val length = number(g, "length") ?: run {
            val end = g.points["End"] ?: return null
            val a0 = atan2(vN, vE)
            val a1 = atan2(end.first - center.first, end.second - center.second)
            var sweep = if (sign > 0) a1 - a0 else a0 - a1
            if (sweep <= 0.0) sweep += 2 * PI
            radius * sweep
        }
        if (!(length > 0.0)) return null
        return AlignmentElement.arc(chainage, length, begin.second, begin.first, azimuth, sign / radius)
    }

    private fun spiral(g: Geometry, begin: Pair<Double, Double>?, chainage: Double, previousEnd: Double?): AlignmentElement? {
        begin ?: return null
        val type = g.attributes["spiType"]
        if (type != null && !type.equals("clothoid", ignoreCase = true)) return null
        val sign = sign(g) ?: return null
        val length = number(g, "length") ?: return null
        if (!(length > 0.0)) return null
        val k0 = curvatureOf(g.attributes["radiusStart"]) ?: return null
        val k1 = curvatureOf(g.attributes["radiusEnd"]) ?: return null
        if (k0 == k1) return null
        val azimuth = number(g, "dirStart")?.let { Math.toRadians(it) }
            ?: previousEnd
            ?: g.points["PI"]?.let { positive(atan2(it.second - begin.second, it.first - begin.first)) }
            ?: return null
        return AlignmentElement.clothoid(chainage, length, begin.second, begin.first, azimuth, sign * k0, sign * k1)
    }

    /** La curvatura de un radio de LandXML: `INF` es una recta (0); null si no es ni `INF` ni un radio positivo. */
    private fun curvatureOf(radius: String?): Double? {
        val text = radius?.trim() ?: return null
        if (text.equals("INF", ignoreCase = true)) return 0.0
        val value = text.toDoubleOrNull() ?: return null
        return if (value > 0.0) 1.0 / value else null
    }

    private fun buildProfile(profiles: List<List<ProfileVertex>>): Pair<List<VerticalVertex>?, ProfileOmission?> {
        if (profiles.isEmpty()) return null to null
        if (profiles.size > 1) return null to ProfileOmission.MultipleProfiles
        val vertices = profiles.single()
        vertices.firstOrNull { it.name == "UnsymParaCurve" }?.let { return null to ProfileOmission.UnsupportedCurve(it.name) }
        return vertices.map { v ->
            val curve = when (v.name) {
                "ParaCurve" -> v.length?.let { VerticalCurve.Parabola(it) } ?: VerticalCurve.None
                "CircCurve" -> if (v.radius != null && v.length != null) VerticalCurve.Circular(v.radius, v.length) else VerticalCurve.None
                else -> VerticalCurve.None
            }
            VerticalVertex(v.chainage, v.elevation, curve)
        } to null
    }
}

package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlStreamReader
import es.topophone.core.model.project.LinearUnit
import java.io.InputStream

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
 * Lee las alineaciones de un LandXML por flujo (S049/RF-2 a RF-5, plan A2), con el mismo `XmlStreamReader` y las mismas comprobaciones de
 * versión y de unidad que las superficies (S042). [scan] lista las alineaciones; [read] entrega las elegidas. Las coordenadas de
 * LandXML van **Norte, Este** y los ángulos en grados decimales.
 */
object LandXmlAlignmentReader {
    /** El mismo límite que las superficies y los dibujos: 100 MB (RF-3). */
    const val MAX_BYTES = 100L * 1024 * 1024

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

                    3 -> if (inProfAlign) {
                        if (xml.name in PROFILE_VERTICES) profileVertices++
                        xml.skipElement()
                    } else {
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

    private val PROFILE_VERTICES = setOf("PVI", "ParaCurve", "CircCurve", "UnsymParaCurve")
}

package es.topophone.core.formats.landxml

import es.topophone.core.formats.xml.XmlStreamReader
import es.topophone.core.model.project.LinearUnit

/** La cabecera de un LandXML que comparten las superficies (S042) y las alineaciones (S049): su versión y su unidad lineal. */
internal class LandXmlHeader {
    var version: String? = null
    var unit: LinearUnit? = null

    companion object {
        private val SUPPORTED_VERSION = Regex("""1\.[0-2](\.\d+)?""")
        private val METRES = setOf("meter", "metre")

        /**
         * Recorre el fichero: comprueba la raíz y la versión, toma la unidad y llama a [onItem] con cada `<[item]>` de dentro de
         * `<[container]>` ya abierto (con su posición y su nombre); [onItem] debe dejar el lector tras el `END` del elemento y devuelve
         * true si ya no hace falta seguir leyendo. [LandXmlException] si no es un LandXML 1.0 a 1.2.
         */
        fun walk(xml: XmlStreamReader, header: LandXmlHeader, container: String, item: String, onItem: (Int, String) -> Boolean) {
            var rootSeen = false
            var index = 0
            while (true) {
                when (xml.next()) {
                    XmlStreamReader.Event.EOF -> {
                        if (!rootSeen) throw LandXmlException(LandXmlProblem.NOT_LANDXML)
                        return
                    }

                    XmlStreamReader.Event.START -> {
                        if (!rootSeen) {
                            rootSeen = true
                            if (xml.name != "LandXML") throw LandXmlException(LandXmlProblem.NOT_LANDXML)
                            val version = xml.attribute("version")
                            header.version = version
                            if (version != null && !SUPPORTED_VERSION.matches(version.trim())) {
                                throw LandXmlException(LandXmlProblem.UNSUPPORTED_VERSION, version)
                            }
                        } else {
                            when (xml.name) {
                                "Units" -> header.unit = units(xml)
                                item -> {
                                    val name = xml.attribute("name") ?: ""
                                    if (onItem(index++, name)) return
                                }
                                container -> Unit
                                else -> if (xml.depth == 2) xml.skipElement()
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }

        /** La unidad del hijo `Metric` o `Imperial` de `Units`, ya leído hasta su `END`; null si no es una de las tres de la app. */
        private fun units(xml: XmlStreamReader): LinearUnit? {
            var unit: LinearUnit? = null
            while (true) {
                when (xml.next()) {
                    XmlStreamReader.Event.START -> when (xml.name) {
                        "Metric" -> unit = if (xml.attribute("linearUnit")?.lowercase() in METRES) LinearUnit.METRE else null
                        "Imperial" -> unit = when (xml.attribute("linearUnit")?.lowercase()) {
                            "foot", "feet" -> LinearUnit.INTERNATIONAL_FOOT
                            "ussurveyfoot" -> LinearUnit.US_SURVEY_FOOT
                            else -> null
                        }
                    }

                    XmlStreamReader.Event.END -> if (xml.name == "Units") return unit
                    XmlStreamReader.Event.EOF -> return unit
                    else -> Unit
                }
            }
        }
    }
}

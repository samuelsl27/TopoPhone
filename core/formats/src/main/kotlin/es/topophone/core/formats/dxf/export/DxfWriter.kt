package es.topophone.core.formats.dxf.export

import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter

/**
 * Escribe un [DxfDocument] como DXF de texto R12 (`AC1009`) en Windows-1252 (S029/RF-3, RF-4, RF-15, plan «Modelo del fichero»): cabecera,
 * tabla de capas, entidades (`POINT`, `TEXT`, `POLYLINE` 3D) y `EOF`, con pares de código y valor terminados en `\r\n`. Los números van con
 * punto, a 4 decimales en coordenadas locales y a 9 en globales (RF-10).
 */
object DxfWriter {
    private const val EOL = "\r\n"
    private const val GROUP_WIDTH = 3
    private const val COMMENT_CODE = 999
    private const val POLYLINE_3D = 8
    private const val POLYLINE_CLOSED = 1
    private const val VERTEX_3D = 32
    private const val BUFFER = 1 shl 16

    /** Escribe [document] en [out] y devuelve cuántos caracteres de texto se han sustituido por `?`. No cierra [out]. */
    fun write(document: DxfDocument, out: OutputStream): Int {
        val decimals = if (document.coordinates ==
            DxfCoordinates.GLOBAL
        ) {
            DxfNumberFormat.DEGREE_DECIMALS
        } else {
            DxfNumberFormat.LINEAR_DECIMALS
        }
        val writer = Pairs(BufferedWriter(OutputStreamWriter(out, DxfCharset.CHARSET), BUFFER), decimals)
        document.comment?.let { writer.pair(COMMENT_CODE, it) } // S058/RF-14
        writer.header()
        writer.layers(document.layers)
        writer.pair(0, "SECTION")
        writer.pair(2, "ENTITIES")
        for (entity in document.entities) writer.entity(entity)
        writer.pair(0, "ENDSEC")
        writer.pair(0, "EOF")
        writer.flush()
        return writer.replaced
    }

    private class Pairs(private val out: BufferedWriter, private val decimals: Int) {
        var replaced = 0
            private set

        fun flush() = out.flush()

        fun pair(code: Int, value: String) {
            out.write(code.toString().padStart(GROUP_WIDTH))
            out.write(EOL)
            out.write(value)
            out.write(EOL)
        }

        private fun number(code: Int, value: Double) = pair(code, DxfNumberFormat.format(value, decimals))

        private fun text(code: Int, value: String) {
            val cleaned = DxfCharset.clean(value)
            replaced += cleaned.replaced
            pair(code, cleaned.text)
        }

        fun header() {
            pair(0, "SECTION")
            pair(2, "HEADER")
            pair(9, "\$ACADVER")
            pair(1, "AC1009")
            pair(0, "ENDSEC")
        }

        fun layers(layers: List<DxfLayerDef>) {
            pair(0, "SECTION")
            pair(2, "TABLES")
            pair(0, "TABLE")
            pair(2, "LAYER")
            pair(70, layers.size.toString())
            for (layer in layers) {
                pair(0, "LAYER")
                text(2, layer.name)
                pair(70, "0")
                pair(62, layer.color.toString())
                pair(6, "CONTINUOUS")
            }
            pair(0, "ENDTAB")
            pair(0, "ENDSEC")
        }

        fun entity(entity: DxfEntity) {
            when (entity) {
                is DxfPointEntity -> {
                    pair(0, "POINT")
                    text(8, entity.layer)
                    xyz(entity.x, entity.y, entity.z)
                }
                is DxfTextEntity -> {
                    pair(0, "TEXT")
                    text(8, entity.layer)
                    xyz(entity.x, entity.y, entity.z)
                    number(40, entity.height)
                    text(1, entity.text)
                }
                is DxfPolylineEntity -> polyline(entity)
            }
        }

        private fun xyz(x: Double, y: Double, z: Double) {
            number(10, x)
            number(20, y)
            number(30, z)
        }

        private fun polyline(entity: DxfPolylineEntity) {
            pair(0, "POLYLINE")
            text(8, entity.layer)
            pair(66, "1")
            pair(70, (POLYLINE_3D + if (entity.closed) POLYLINE_CLOSED else 0).toString())
            for (vertex in entity.vertices) {
                pair(0, "VERTEX")
                text(8, entity.layer)
                xyz(vertex.x, vertex.y, vertex.z)
                pair(70, VERTEX_3D.toString())
            }
            pair(0, "SEQEND")
            text(8, entity.layer)
        }
    }
}

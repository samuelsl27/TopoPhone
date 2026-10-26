package es.topophone.core.formats.dxf

import es.topophone.core.model.cad.CadEntity
import es.topophone.core.model.cad.CadEntityKind
import es.topophone.core.model.cad.CadExtent
import es.topophone.core.model.cad.CadIgnored
import es.topophone.core.model.project.LinearUnit
import java.io.InputStream

/**
 * Cómo acabó la lectura de un DXF (S040/RF-13): su cabecera, sus capas, y si [complete]. Un fichero cortado, con un código de
 * grupo que no es un número o sin sección ENTITIES queda incompleto; lo que se leyó antes ya se ha entregado.
 */
class DxfReadResult(val header: DxfHeader, val tables: DxfTables, val complete: Boolean)

/** Lee un DXF de texto de punta a punta y entrega sus elementos a un [DxfSink] (S040/RF-13, RF-14, RF-16). */
object DxfReader {
    /**
     * Recorre `HEADER`, `TABLES`, `BLOCKS` y `ENTITIES` en el orden en que vienen y se salta el resto. Las curvas se pasan a
     * polilíneas con la tolerancia de 1 cm expresada en la [unit] del dibujo (RF-15). Los elementos salen en las coordenadas del
     * dibujo, sin convertir.
     */
    fun read(input: InputStream, unit: LinearUnit, sink: DxfSink): DxfReadResult {
        val tags = DxfTagReader(input)
        var header = DxfHeader()
        var tables = DxfTables(emptyMap())
        var blocks: Map<String, DxfBlock> = emptyMap()
        var complete = false
        try {
            sections@ while (true) {
                val tag = tags.next() ?: break
                if (tag.code != 0) continue
                when (tag.value.trim()) {
                    "EOF" -> break@sections
                    "SECTION" -> {
                        val name = tags.next()?.takeIf { it.code == 2 }?.value?.trim() ?: break@sections
                        when (name) {
                            "HEADER" -> header = DxfHeaderParser.parse(tags)
                            "TABLES" -> tables = DxfTables.parse(tags)
                            "BLOCKS" -> blocks = DxfBlocks.parse(tags)
                            "ENTITIES" -> {
                                complete = entities(tags, tables, blocks, unit, sink)
                                if (!complete) break@sections
                            }
                            else -> skip(tags)
                        }
                    }
                }
            }
        } catch (_: DxfFormatException) {
            complete = false
        }
        return DxfReadResult(header, tables, complete)
    }

    /** Lee la sección ENTITIES; true si acabó en su `ENDSEC` (o en el `EOF`). */
    private fun entities(tags: DxfTagReader, tables: DxfTables, blocks: Map<String, DxfBlock>, unit: LinearUnit, sink: DxfSink): Boolean {
        sink.tables(tables)
        val expander = DxfExpander(tables, blocks, CadCurves.TOLERANCE_M / unit.metres, sink)
        val stream = DxfEntityStream(tags)
        while (true) {
            val item = stream.next() ?: break
            expander.accept(item)
        }
        return !stream.truncated
    }

    private fun skip(tags: DxfTagReader) {
        while (true) {
            val tag = tags.next() ?: return
            if (tag.code == 0 && tag.value.trim() == "ENDSEC") return
        }
    }
}

/**
 * Un [DxfSink] que no guarda los elementos: solo el recuadro del dibujo, cuántos hay de cada tipo y cuántos se ignoran. Es la
 * exploración que da la extensión antes de importar (S040/RF-9).
 */
class DxfExtentSink : DxfSink {
    private var minE = Double.POSITIVE_INFINITY
    private var maxE = Double.NEGATIVE_INFINITY
    private var minN = Double.POSITIVE_INFINITY
    private var maxN = Double.NEGATIVE_INFINITY

    var count: Int = 0
        private set

    var ignoredCount: Int = 0
        private set

    private val kinds = HashMap<CadEntityKind, Int>()

    val countsByKind: Map<CadEntityKind, Int> get() = kinds

    /** El recuadro en las coordenadas del dibujo; null si no hubo ningún elemento. */
    val extent: CadExtent? get() = if (count == 0) null else CadExtent(minE, maxE, minN, maxN)

    override fun element(layer: String, entity: CadEntity) {
        count++
        kinds.merge(entity.kind, 1, Int::plus)
        for (i in 0 until entity.vertexCount) {
            val e = entity.easting[i]
            val n = entity.northing[i]
            if (e < minE) minE = e
            if (e > maxE) maxE = e
            if (n < minN) minN = n
            if (n > maxN) maxN = n
        }
    }

    override fun ignored(ignored: CadIgnored) {
        ignoredCount++
    }
}

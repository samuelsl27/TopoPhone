package es.topophone.core.formats.dxf

import es.topophone.core.model.cad.CadColor
import kotlin.math.abs

/**
 * La paleta estándar de AutoCAD (ACI) de 255 colores (S040/RF-24). Del 1 al 9 son fijos; del 10 al 249 son 24 tonos, de 15° en
 * 15°, de diez entradas cada uno (saturación plena y mitad, con el valor 100, 80, 60, 50 y 30 %); del 250 al 255, grises.
 */
object AciPalette {
    private val FIXED = intArrayOf(
        0xFF0000, 0xFFFF00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFFFFFF, 0x414141, 0x808080,
    )
    private val GRAYS = intArrayOf(0x333333, 0x505050, 0x696969, 0x828282, 0xBEBEBE, 0xFFFFFF)
    private val VALUES = intArrayOf(255, 255, 204, 204, 153, 153, 127, 127, 76, 76)

    /** El color 0xRRGGBB del índice [index] (1 a 255); null para BYBLOCK (0), BYLAYER (256) y lo que no es un índice. */
    fun rgb(index: Int): Int? = when (index) {
        in 1..9 -> FIXED[index - 1]
        in 10..249 -> hue(index)
        in 250..255 -> GRAYS[index - 250]
        else -> null
    }

    private fun hue(index: Int): Int {
        val group = (index - 10) / 10
        val inGroup = (index - 10) % 10
        val v = VALUES[inGroup]
        val sector = group / 4
        val f = group % 4 // cuartos de sector
        val up = v * f / 4
        val down = v * (4 - f) / 4
        val full = when (sector) {
            0 -> intArrayOf(v, up, 0)
            1 -> intArrayOf(down, v, 0)
            2 -> intArrayOf(0, v, up)
            3 -> intArrayOf(0, down, v)
            4 -> intArrayOf(up, 0, v)
            else -> intArrayOf(v, 0, down)
        }
        // La saturación a la mitad acerca cada componente al valor máximo: (v + componente) / 2.
        val c = if (inGroup % 2 == 0) full else IntArray(3) { (v + full[it]) / 2 }
        return (c[0] shl 16) or (c[1] shl 8) or c[2]
    }
}

/** Cómo se pasa un color del DXF al de la capa de diseño (S040/RF-24, RF-25). */
object DxfColors {
    private const val BY_BLOCK = 0
    private const val BY_LAYER = 256
    private const val WHITE_OR_BLACK = 7
    private const val WHITE = 0xFFFFFF
    private const val BLACK = 0x000000

    /** El color del índice ACI [index]; el 7 (blanco o negro según el fondo del programa) es el color de contraste. */
    fun aci(index: Int): CadColor {
        val rgb = if (index == WHITE_OR_BLACK) null else AciPalette.rgb(index)
        return if (rgb == null) CadColor.AUTO_CONTRAST else CadColor.rgb(rgb)
    }

    /** Un color verdadero 0xRRGGBB (código 420); el blanco y el negro puros son el color de contraste. */
    fun trueColor(rgb: Int): CadColor = if (rgb == WHITE || rgb == BLACK) CadColor.AUTO_CONTRAST else CadColor.rgb(rgb)

    /**
     * El color de un elemento: el verdadero si lo trae (manda sobre el ACI); si no, el ACI propio; con BYBLOCK (0), el del INSERT
     * si está dentro de un bloque; con BYLAYER (256) o sin color, el de su capa. Un ACI negativo cuenta como su valor absoluto.
     */
    fun entity(aci: Int?, trueColor: Int?, layerColor: CadColor, insertColor: CadColor?): CadColor {
        if (trueColor != null) return trueColor(trueColor)
        val index = aci?.let { abs(it) }
        return when {
            index == null || index == BY_LAYER -> layerColor
            index == BY_BLOCK -> insertColor ?: layerColor
            else -> aci(index)
        }
    }
}

/**
 * Una capa de la tabla LAYER (S040/RF-24, RF-33): su nombre, su color ACI (negativo si está apagada), sus marcas (el bit 1, congelada)
 * y su color verdadero si lo trae.
 */
data class DxfLayer(val name: String, val aci: Int, val flags: Int, val trueColor: Int?) {
    /** Apagada o congelada en el propio fichero: llega oculta (RF-33). */
    val hidden: Boolean get() = aci < 0 || flags and FROZEN != 0

    val color: CadColor get() = DxfColors.entity(aci = aci, trueColor = trueColor, layerColor = CadColor.AUTO_CONTRAST, insertColor = null)

    private companion object {
        const val FROZEN = 1
    }
}

/** Las capas del DXF por su nombre (S040/RF-24, RF-33). */
class DxfTables(val layers: Map<String, DxfLayer>) {
    fun layer(name: String): DxfLayer? = layers[name]

    /** El color de la capa [name]; el de contraste si el fichero no la declara. */
    fun colorOf(name: String): CadColor = layers[name]?.color ?: CadColor.AUTO_CONTRAST

    companion object {
        /** Lee la sección TABLES, ya abierta, hasta su `ENDSEC`, y se queda con la tabla LAYER. */
        fun parse(tags: DxfTagReader): DxfTables {
            val layers = LinkedHashMap<String, DxfLayer>()
            var table: String? = null
            var name: String? = null
            var aci = 7
            var flags = 0
            var trueColor: Int? = null
            var inRecord = false

            fun flush() {
                if (inRecord && name != null) layers.putIfAbsent(name!!, DxfLayer(name!!, aci, flags, trueColor))
                inRecord = false
                name = null
                aci = 7
                flags = 0
                trueColor = null
            }

            while (true) {
                val tag = tags.next() ?: break
                if (tag.code == 0) {
                    flush()
                    when (tag.value.trim()) {
                        "ENDSEC" -> break
                        "TABLE" -> table = null
                        "ENDTAB" -> table = null
                        "LAYER" -> inRecord = table == "LAYER"
                    }
                    if (tag.value.trim() == "TABLE") {
                        val header = tags.next() ?: break
                        if (header.code == 2) table = header.value.trim() else tags.unread(header)
                    }
                } else if (inRecord) {
                    when (tag.code) {
                        2 -> name = tag.value
                        62 -> aci = tag.int() ?: aci
                        70 -> flags = tag.int() ?: flags
                        420 -> trueColor = tag.int()?.and(0xFFFFFF)
                    }
                }
            }
            flush()
            return DxfTables(layers)
        }
    }
}

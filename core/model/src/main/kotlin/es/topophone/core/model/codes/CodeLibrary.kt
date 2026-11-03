package es.topophone.core.model.codes

/** Lo que representa un código de levantamiento (S023). */
enum class CodeGeometry { POINT, LINE, POLYGON }

/** El tipo de un atributo que se anota en cada objeto con un código (S023/RF-9). */
enum class AttributeType { TEXT, INTEGER, NUMBER, LIST }

/**
 * Un atributo de un código (S023/RF-9). [defaultValue] es un texto canónico (Entero con `toLong`, Número con `Double.toString`);
 * [options] solo vale en una Lista.
 */
data class CodeAttribute(
    val name: String,
    val type: AttributeType,
    val defaultValue: String? = null,
    val options: List<String> = emptyList(),
)

/** Un código de levantamiento de una biblioteca (S023/RF-5). [color] es 0xRRGGBB. */
data class SurveyCode(
    val id: String,
    val code: String,
    val description: String,
    val geometry: CodeGeometry,
    val color: Int,
    val attributes: List<CodeAttribute> = emptyList(),
)

/** Una biblioteca de códigos; las [isDefault] vienen con la app y son de solo lectura (S023/RF-3). */
data class CodeLibrary(val id: String, val name: String, val isDefault: Boolean, val codes: List<SurveyCode>)

/** Qué hace un proyecto con su biblioteca vinculada al guardarse (S023/RF-19, RF-21). */
sealed interface LibraryChoice {
    /** No tocar la copia que lleva ahora. */
    data object Keep : LibraryChoice

    /** Quitar la biblioteca vinculada. */
    data object None : LibraryChoice

    /** Vincular una copia de la biblioteca [id]. */
    data class Library(val id: String) : LibraryChoice
}

/** La paleta de colores de los códigos y su escritura como `#RRGGBB` (S023/RF-8, RF-13). */
object CodePalette {
    val COLORS: List<Int> = listOf(
        0xD32F2F, 0xE64A19, 0xF9A825, 0x689F38, 0x00796B, 0x0288D1,
        0x1E88E5, 0x3949AB, 0x7B1FA2, 0xC2185B, 0x5D4037, 0x37474F,
    )

    /** El color de un código sin color elegido, según su geometría (S023/RF-8). */
    fun defaultFor(geometry: CodeGeometry): Int = when (geometry) {
        CodeGeometry.POINT -> 0x1E88E5
        CodeGeometry.LINE -> 0xE64A19
        CodeGeometry.POLYGON -> 0x689F38
    }

    fun format(rgb: Int): String = "#%06X".format(java.util.Locale.ROOT, rgb and 0xFFFFFF)

    /** `#RRGGBB` (sin distinguir mayúsculas, con espacios a los lados) o null. */
    fun parse(text: String): Int? {
        val t = text.trim()
        if (t.length != 7 || t[0] != '#') return null
        if (!t.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return t.drop(1).toInt(16)
    }
}

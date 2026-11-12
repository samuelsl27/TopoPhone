package es.topophone.core.formats.kml

/** Los colores de un `Style` de KML que le importan a la app: el de `IconStyle` (puntos) y el de `LineStyle` (líneas y contornos) (S031/RF-12). */
data class KmlStyle(val iconColor: Int? = null, val lineColor: Int? = null)

/** A qué se aplica el color de un estilo: los puntos usan `IconStyle`; las líneas y los contornos de polígono, `LineStyle`. */
enum class KmlColorTarget { POINT, LINE }

/** El color ARGB de este estilo para [target], o `null` si no lo declara o no hay estilo. */
fun KmlStyle?.colorFor(target: KmlColorTarget): Int? = when (target) {
    KmlColorTarget.POINT -> this?.iconColor
    KmlColorTarget.LINE -> this?.lineColor
}

/** El estilo `normal` de un `StyleMap`: uno escrito dentro del `Pair` o una referencia `#id` (la `highlight` no se lee). */
sealed interface KmlStyleMapNormal {
    data class Inline(val style: KmlStyle) : KmlStyleMapNormal

    data class Reference(val url: String) : KmlStyleMapNormal
}

/**
 * Los estilos de un documento KML por su `id`, a medida que se leen (S031/RF-12, plan A2.2). Una marca resuelve su estilo **al terminar**
 * con los que se han declarado hasta entonces: uno declarado después no se aplica a ella. El estilo en la marca gana al `styleUrl`; este
 * solo vale si empieza por `#` (los de otros ficheros no se leen) y un `StyleMap` da su estilo `normal`, con hasta [MAX_REFERENCES] saltos.
 */
class KmlStyleTable {
    private val styles = HashMap<String, KmlStyle>()
    private val styleMaps = HashMap<String, KmlStyleMapNormal>()

    /** Un `Style` con `id`. Si el identificador ya existía como estilo o como `StyleMap`, este gana. */
    fun addStyle(id: String, style: KmlStyle) {
        styleMaps.remove(id)
        styles[id] = style
    }

    /** Un `StyleMap` con `id` y su estilo normal. */
    fun addStyleMap(id: String, normal: KmlStyleMapNormal) {
        styles.remove(id)
        styleMaps[id] = normal
    }

    /** El estilo de una marca con [inline] (su `Style`) y [styleUrl]; `null` si no tiene ninguno o no se resuelve. */
    fun resolve(inline: KmlStyle?, styleUrl: String?): KmlStyle? = inline ?: styleUrl?.let { resolve(it, 0) }

    private fun resolve(url: String, hops: Int): KmlStyle? {
        if (hops > MAX_REFERENCES || !url.startsWith('#')) return null
        val id = url.substring(1)
        styles[id]?.let { return it }
        return when (val normal = styleMaps[id]) {
            null -> null
            is KmlStyleMapNormal.Inline -> normal.style
            is KmlStyleMapNormal.Reference -> resolve(normal.url, hops + 1)
        }
    }

    private companion object {
        /** Los saltos de `StyleMap` a `StyleMap` que se siguen: la cadena de la referencia de `tools` (profundidad 4). */
        const val MAX_REFERENCES = 4
    }
}

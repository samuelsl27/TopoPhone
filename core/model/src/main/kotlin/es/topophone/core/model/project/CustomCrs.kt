package es.topophone.core.model.project

/** Código «EPSG» de un SC personalizado: no tiene uno de verdad (S035; plan, modelo de datos). */
const val CUSTOM_EPSG = 0

/** ¿Es un SC definido por el usuario y no uno de la biblioteca de S034? */
val CrsDefinition.isCustom: Boolean get() = epsg == CUSTOM_EPSG

/** La altura de un SC personalizado (S035/RF-5). */
sealed interface CustomHeight {
    /** Altura elipsoidal. */
    data object Ellipsoidal : CustomHeight

    /** Altitud con el geoide [grid] (una rejilla del CDN de PROJ instalada, S034). */
    data class Geoid(val grid: String) : CustomHeight

    /** Altura ortométrica constante respecto al elipsoide: [undulationM] es la ondulación del geoide, igual en todo el proyecto. */
    data class ConstantGeoid(val undulationM: Double) : CustomHeight
}

/**
 * Un SC de «Mis SC» (S035/RF-9): una [CrsDefinition] con [CUSTOM_EPSG], su altura y las marcas de tiempo. El nombre es el de [name] y es único sin
 * distinguir mayúsculas (RF-10).
 */
data class CustomCrs(
    val id: String,
    val name: String,
    val definition: CrsDefinition,
    val height: CustomHeight = CustomHeight.Ellipsoidal,
    val createdAtMs: Long = 0,
    val modifiedAtMs: Long = 0,
) {
    /** El SC del proyecto que sale de elegir este SC (S035/RF-11: el proyecto guarda su copia de la definición). */
    fun toProjectCrs(corrections: CorrectionsDatum): LibraryCrs = LibraryCrs(
        definition = definition.copy(name = name),
        correctionsDatum = corrections,
        geoidGrid = (height as? CustomHeight.Geoid)?.grid,
        geoidConstantM = (height as? CustomHeight.ConstantGeoid)?.undulationM,
    )
}

/**
 * El SC de un proyecto como texto, para el historial del proyecto (S035/RF-20). Los de España van por su nombre (`SPAIN|…`) y los de la biblioteca o
 * personalizados con su definición completa (`LIB|…`), de modo que se pueden recuperar aunque el SC ya no esté en Mis SC.
 */
object ProjectCrsText {
    private const val SPAIN = "SPAIN"
    private const val LIBRARY = "LIB"
    private const val FIELDS = 5

    fun encode(crs: ProjectCrs): String = when (crs) {
        is SpainCrs -> "$SPAIN|${crs.name}"
        is LibraryCrs -> listOf(
            LIBRARY,
            crs.correctionsDatum.name,
            crs.geoidGrid.orEmpty(),
            crs.geoidConstantM?.toString().orEmpty(),
            crs.definition.toText(),
        ).joinToString("|")
    }

    /** `null` si el texto no es de un SC conocido. */
    fun decodeOrNull(text: String): ProjectCrs? {
        if (text.startsWith("$SPAIN|")) return SpainCrs.entries.firstOrNull { it.name == text.removePrefix("$SPAIN|") }
        if (!text.startsWith("$LIBRARY|")) return null
        // La definición puede llevar `|` en los pasos de la transformación: solo se parten los cuatro primeros campos.
        val f = text.split('|', limit = FIELDS)
        if (f.size != FIELDS) return null
        val definition = CrsDefinition.parseOrNull(f[4]) ?: return null
        val corrections = CorrectionsDatum.entries.firstOrNull { it.name == f[1] } ?: return null
        val constant = if (f[3].isEmpty()) null else f[3].toDoubleOrNull() ?: return null
        if (f[2].isNotEmpty() && constant != null) return null
        return LibraryCrs(definition, corrections, f[2].ifEmpty { null }, constant)
    }
}

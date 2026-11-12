package es.topophone.core.formats.shp.export

import es.topophone.core.formats.csv.AttributeColumn
import es.topophone.core.formats.csv.ExportColumn
import java.text.Normalizer
import java.util.Locale

/** El idioma de los nombres de las columnas fijas: el de la interfaz en el momento de exportar (S055/RF-8). */
enum class ShpNameLanguage { ES, EN }

/** Las columnas fijas de las capas de líneas y de polígonos (S055/RF-7), en su orden. */
enum class ShpLineColumn { NAME, CODE, VERTICES }

/** Un atributo de código cuyo nombre ha cambiado al escribirlo: el [original] y el [name] que lleva en el `.dbf` (S055/RF-9, RF-22). */
data class ShpRenamedColumn(val original: String, val name: String)

/** Los [names] de las columnas de unos atributos de código, en el mismo orden, y los que han cambiado respecto al original ([renamed]). */
data class ShpAttributeNames(val names: List<String>, val renamed: List<ShpRenamedColumn>)

/**
 * Los nombres de las columnas del `.dbf` (S055/RF-8, RF-9): un `.dbf` no admite más de 10 caracteres por nombre. Las columnas fijas llevan un
 * nombre abreviado de una tabla (plan, «Tabla de nombres fijos»), en mayúsculas, sin acentos ni eñes y en el idioma de la interfaz; los
 * atributos de los códigos, el suyo limpiado y recortado, distinto del de cualquier otra columna de la capa.
 */
object ShpColumnNames {
    /** La longitud máxima del nombre de una columna de un `.dbf`. */
    const val MAX_LENGTH = 10

    /** El nombre de [column] en [language] (S055/RF-8). */
    fun of(column: ExportColumn, language: ShpNameLanguage): String {
        val (es, en) = when (column) {
            ExportColumn.NAME -> "NOMBRE" to "NAME"
            ExportColumn.DESCRIPTION -> "DESCRIP" to "DESCR"
            ExportColumn.ORIGIN -> "ORIGEN" to "ORIGIN"
            ExportColumn.EASTING -> "ESTE" to "EASTING"
            ExportColumn.NORTHING -> "NORTE" to "NORTHING"
            ExportColumn.ELEVATION -> "COTA" to "ELEVATION"
            ExportColumn.LATITUDE -> "LATITUD" to "LATITUDE"
            ExportColumn.LONGITUDE -> "LONGITUD" to "LONGITUDE"
            ExportColumn.ELLIPSOIDAL_HEIGHT -> "ALT_ELIP" to "ELLIP_HT"
            ExportColumn.CRS -> "SC" to "CRS"
            ExportColumn.HEIGHT_SYSTEM -> "SIST_ALT" to "HT_SYSTEM"
            ExportColumn.UNITS -> "UNIDADES" to "UNITS"
            ExportColumn.POLE_HEIGHT -> "ALT_JALON" to "POLE_HT"
            ExportColumn.SOLUTION -> "SOLUCION" to "SOLUTION"
            ExportColumn.SATELLITES -> "SATELITES" to "SATS"
            ExportColumn.HDOP -> "HDOP" to "HDOP"
            ExportColumn.CORRECTION_AGE -> "EDAD_CORR" to "CORR_AGE"
            ExportColumn.HORIZONTAL_ACCURACY -> "PREC_H" to "ACC_H"
            ExportColumn.VERTICAL_ACCURACY -> "PREC_V" to "ACC_V"
            ExportColumn.MODE -> "MODO" to "MODE"
            ExportColumn.EPOCHS_USED -> "EPOC_USAD" to "EPOCH_USED"
            ExportColumn.EPOCHS_DISCARDED -> "EPOC_DESC" to "EPOCH_DISC"
            ExportColumn.REPLAY -> "REPRODUC" to "REPLAY"
            ExportColumn.OUT_OF_TOLERANCE -> "FUERA_TOL" to "OUT_TOL"
            ExportColumn.MEASURED_FROM -> "MED_INICIO" to "MEAS_FROM"
            ExportColumn.MEASURED_TO -> "MED_FIN" to "MEAS_TO"
            ExportColumn.BASE_LATITUDE -> "BASE_LAT" to "BASE_LAT"
            ExportColumn.BASE_LONGITUDE -> "BASE_LON" to "BASE_LON"
            ExportColumn.BASE_ELLIPSOIDAL_HEIGHT -> "BASE_ELIP" to "BASE_ELLIP"
            ExportColumn.BASE_EASTING -> "BASE_ESTE" to "BASE_E"
            ExportColumn.BASE_NORTHING -> "BASE_NORTE" to "BASE_N"
            ExportColumn.BASE_ELEVATION -> "BASE_COTA" to "BASE_ELEV"
            ExportColumn.BASELINE -> "LINEABASE" to "BASELINE"
            ExportColumn.TILT_MODE -> "MODO_INCL" to "TILT_MODE"
            ExportColumn.TILT_MODE_VERIFIED -> "INCL_VERIF" to "TILT_VERIF"
            ExportColumn.TILT -> "INCLINAC" to "TILT"
            ExportColumn.TILT_DIRECTION -> "DIR_INCL" to "TILT_DIR"
            ExportColumn.HEADING -> "RUMBO" to "HEADING"
            ExportColumn.MAX_TILT -> "INCL_MAX" to "MAX_TILT"
            ExportColumn.TILT_HORIZONTAL_ACCURACY -> "INCL_PRC_H" to "TILT_ACC_H"
            ExportColumn.TILT_VERTICAL_ACCURACY -> "INCL_PRC_V" to "TILT_ACC_V"
            ExportColumn.AVERAGED_TIME -> "T_PROMEDIO" to "AVG_TIME"
            ExportColumn.DEVIATION_HORIZONTAL -> "DESV_H" to "DEV_H"
            ExportColumn.DEVIATION_VERTICAL -> "DESV_V" to "DEV_V"
            ExportColumn.AUTOMATIC -> "AUTOMATICO" to "AUTOMATIC"
            ExportColumn.EDITED -> "EDITADO" to "EDITED"
            ExportColumn.COPY_OF -> "COPIA_DE" to "COPY_OF"
            ExportColumn.STAKE_DESIGN -> "REPL_DISEN" to "STK_DESIGN"
            ExportColumn.STAKE_DESIGN_ELEVATION -> "REPL_COTAD" to "STK_DES_EL"
            ExportColumn.STAKE_D_NORTH -> "REPL_DN" to "STK_DN"
            ExportColumn.STAKE_D_EAST -> "REPL_DE" to "STK_DE"
            ExportColumn.STAKE_D_HORIZONTAL -> "REPL_DH" to "STK_DH"
            ExportColumn.STAKE_D_ELEVATION -> "REPL_DZ" to "STK_DZ"
            ExportColumn.STAKE_WITHIN -> "REPL_TOL" to "STK_WITHIN"
            ExportColumn.SHIFT_DX -> "SHIFT_DX" to "SHIFT_DX"
            ExportColumn.SHIFT_DY -> "SHIFT_DY" to "SHIFT_DY"
            ExportColumn.SHIFT_DZ -> "SHIFT_DZ" to "SHIFT_DZ"
            ExportColumn.CODE -> "CODIGO" to "CODE"
        }
        return if (language == ShpNameLanguage.ES) es else en
    }

    /** El nombre de [column], una columna fija de las capas de líneas y de polígonos, en [language] (S055/RF-7). */
    fun of(column: ShpLineColumn, language: ShpNameLanguage): String = when (column) {
        ShpLineColumn.NAME -> if (language == ShpNameLanguage.ES) "NOMBRE" else "NAME"
        ShpLineColumn.CODE -> if (language == ShpNameLanguage.ES) "CODIGO" else "CODE"
        ShpLineColumn.VERTICES -> "VERTICES"
    }

    /** Los nombres de las columnas fijas de la capa de puntos, en el orden de "Todas las columnas". */
    fun fixedPointNames(language: ShpNameLanguage): List<String> = ExportColumn.entries.map { of(it, language) }

    /** Los nombres de las columnas fijas de las capas de líneas y de polígonos. */
    fun fixedLineNames(language: ShpNameLanguage): List<String> = ShpLineColumn.entries.map { of(it, language) }

    /**
     * Los nombres de las columnas de [attributes], distintos entre sí y de los de [taken] (RF-9): el nombre del atributo en mayúsculas, sin
     * acentos, con `_` en lugar de lo que no sea una letra o una cifra y de hasta [MAX_LENGTH] caracteres. Si coincide con otra columna, se
     * recorta lo justo para añadirle un número al final (`COTA_ALT1`, `COTA_ALT2`…). Los que cambian respecto al nombre original, sin contar
     * las mayúsculas, van en [ShpAttributeNames.renamed] para el resumen.
     */
    fun forAttributes(attributes: List<AttributeColumn>, taken: Collection<String>): ShpAttributeNames {
        val used = taken.mapTo(HashSet()) { it.uppercase(Locale.ROOT) }
        val names = ArrayList<String>(attributes.size)
        val renamed = ArrayList<ShpRenamedColumn>()
        for (attribute in attributes) {
            val clean = clean(attribute.header)
            var name = clean.take(MAX_LENGTH)
            var n = 1
            while (name in used) {
                val suffix = n.toString()
                name = clean.take(MAX_LENGTH - suffix.length) + suffix
                n++
            }
            used += name
            names += name
            if (!name.equals(attribute.header, ignoreCase = true)) renamed += ShpRenamedColumn(attribute.header, name)
        }
        return ShpAttributeNames(names, renamed)
    }

    /** [text] sin acentos, en mayúsculas y con `_` en lugar de todo lo que no sea una letra ASCII, una cifra o `_`. */
    private fun clean(text: String): String {
        val plain = Normalizer.normalize(text, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
        return plain.uppercase(Locale.ROOT).map { if (it in 'A'..'Z' || it in '0'..'9' || it == '_') it else '_' }.joinToString("")
    }
}

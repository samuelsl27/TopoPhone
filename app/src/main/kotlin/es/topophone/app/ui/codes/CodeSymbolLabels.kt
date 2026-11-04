package es.topophone.app.ui.codes

import es.topophone.app.R
import es.topophone.core.model.codes.CodeSymbol

/** El nombre en pantalla de un símbolo del mapa (S024/RF-5, RF-17). */
fun CodeSymbol.labelRes(): Int = when (this) {
    CodeSymbol.CIRCLE -> R.string.codes_symbol_circle
    CodeSymbol.SQUARE -> R.string.codes_symbol_square
    CodeSymbol.TRIANGLE -> R.string.codes_symbol_triangle
    CodeSymbol.DIAMOND -> R.string.codes_symbol_diamond
    CodeSymbol.CROSS -> R.string.codes_symbol_cross
    CodeSymbol.STAR -> R.string.codes_symbol_star
}

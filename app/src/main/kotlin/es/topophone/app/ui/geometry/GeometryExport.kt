package es.topophone.app.ui.geometry

import es.topophone.core.formats.csv.GeometryCsv
import es.topophone.core.formats.csv.GeometryCsvSegment
import es.topophone.core.formats.csv.GeometryCsvTotals

/** De la información que se ve en pantalla al CSV de la tabla de segmentos (S026/RF-8). */
internal object GeometryExport {

    /** Escribe la tabla de [state] en [out]; sin información no escribe nada. */
    fun write(state: GeometryInfoUi, out: Appendable) {
        val info = state.info ?: return
        val segments = info.segments.map {
            GeometryCsvSegment(
                number = it.index + 1,
                fromName = state.names.getOrElse(it.from) { "" },
                toName = state.names.getOrElse(it.to) { "" },
                chainageM = it.chainage,
                length2DM = it.length2D,
                length3DM = it.length3D,
                azimuthRad = it.azimuthRad,
                deltaHeightM = it.deltaHeight,
                slopePercent = it.slopePercent,
            )
        }
        GeometryCsv.write(
            segments,
            GeometryCsvTotals(info.length2D, info.length3D, info.perimeter, info.area),
            info.mode,
            state.linearUnit,
            state.angularUnit,
            out,
        )
    }
}

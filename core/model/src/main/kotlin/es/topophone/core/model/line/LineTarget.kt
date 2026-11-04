package es.topophone.core.model.line

import es.topophone.core.model.codes.CodeGeometry
import es.topophone.core.model.codes.CodeKey
import es.topophone.core.model.codes.PointCode

/** Qué hacer con un punto que se acaba de medir respecto de las líneas (S025/RF-2, RF-3, RF-7, RF-8). */
sealed interface LineAction {
    /** El punto no es de línea, o ya es el último vértice: la línea activa no cambia. */
    data object Nothing : LineAction

    /** Añadir el punto como último vértice de la línea activa [lineId]. */
    data class Append(val lineId: String) : LineAction

    /** Crear una línea nueva con el punto como primer vértice; pasa a ser la activa y la anterior deja de serlo. */
    data class Create(val geometry: CodeGeometry) : LineAction
}

/** Decide qué pasa con las líneas al medir un punto (S025/A1). */
object LineTarget {
    /** Si [geometry] es de las que forman línea. */
    fun isLineGeometry(geometry: CodeGeometry?): Boolean = geometry == CodeGeometry.LINE || geometry == CodeGeometry.POLYGON

    /**
     * [active] es la línea activa del proyecto; [code] y [geometry] son el código y la geometría con que se mide
     * [pointId]. Sin código o con uno de punto, no pasa nada y la línea activa sigue activa (RF-8); con el mismo
     * código que la línea activa, se añade (RF-3) salvo que sea ya su último vértice (RF-7); en otro caso, se
     * crea una línea nueva (RF-2, RF-8).
     */
    fun decide(active: ProjectLine?, code: PointCode?, geometry: CodeGeometry?, pointId: String): LineAction {
        if (code == null || !isLineGeometry(geometry)) return LineAction.Nothing
        val sameCode = active?.code?.let { CodeKey.of(it.code) == CodeKey.of(code.code) } == true
        if (active == null || !sameCode) return LineAction.Create(geometry!!)
        if (active.vertices.lastOrNull()?.pointId == pointId) return LineAction.Nothing
        return LineAction.Append(active.id)
    }

    /** Si medir con [code] crearía una línea nueva: el formulario de atributos del recolector se muestra solo entonces (RF-8b). */
    fun wouldCreate(active: ProjectLine?, code: PointCode?, geometry: CodeGeometry?): Boolean =
        decide(active, code, geometry, pointId = "") is LineAction.Create
}

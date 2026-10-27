package es.topophone.core.survey

import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.SolutionType

/** Del estado de la GGA al tipo de solución que se admite o no (S047). */
object SolutionTypes {
    /** El tipo de [status]; una DGPS cuenta como SINGLE (S047/RF-18) y una solución estimada o sin posición no tiene tipo. */
    fun of(status: SolutionStatus): SolutionType? = when (status) {
        SolutionStatus.FIX -> SolutionType.FIX
        SolutionStatus.FLOAT -> SolutionType.FLOAT
        SolutionStatus.SINGLE, SolutionStatus.DGPS -> SolutionType.SINGLE
        SolutionStatus.ESTIMATED, SolutionStatus.NONE -> null
    }
}

package es.topophone.core.model.project

/** De dónde sale una fila del historial del proyecto: un cambio de SC o un «Volver al SC anterior» (S035/RF-21). */
enum class CrsChangeKind { CHANGE, UNDO }

/**
 * Un cambio del SC de un proyecto, con su fecha y el SC y el sistema de alturas de antes y de después (S035/RF-20): el historial del proyecto, del más
 * reciente al más antiguo. Los SC van completos (con su definición), de modo que se pueden recuperar aunque ya no estén en Mis SC.
 */
data class ProjectCrsChange(
    val id: String,
    val projectId: String,
    val atMs: Long,
    val from: ProjectCrs,
    val fromHeightSystem: HeightSystem,
    val to: ProjectCrs,
    val toHeightSystem: HeightSystem,
    val kind: CrsChangeKind,
)

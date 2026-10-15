package es.topophone.core.model.project

/** Datos de un proyecto que el usuario escribe al crearlo o editarlo (S006/RF-5, RF-14). */
data class ProjectDraft(
    val name: String,
    val author: String,
    val description: String,
    val crs: ProjectCrs,
    val linearUnit: LinearUnit,
    val angularUnit: AngularUnit,
    val correctionsDatum: CorrectionsDatum,
)

/** Un proyecto guardado (S006). Fechas en ms UTC; con [deletedAtMs] está en la papelera. */
data class Project(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val crs: ProjectCrs,
    val linearUnit: LinearUnit,
    val angularUnit: AngularUnit,
    val correctionsDatum: CorrectionsDatum,
    val folderId: String?,
    val createdAtMs: Long,
    val modifiedAtMs: Long,
    val openedAtMs: Long?,
    val deletedAtMs: Long?,
) {
    fun toDraft() = ProjectDraft(name, author, description, crs, linearUnit, angularUnit, correctionsDatum)
}

/**
 * Valores por defecto de los proyectos nuevos, que se eligen en Ajustes (S006/RF-6, RF-13). Los de
 * fábrica: ETRS89 / UTM 30, metros, grados-minutos-segundos y correcciones en ETRS89, sin autor.
 */
data class ProjectDefaults(
    val author: String = "",
    val crs: ProjectCrs = ProjectCrs.ETRS89_UTM30,
    val linearUnit: LinearUnit = LinearUnit.METRE,
    val angularUnit: AngularUnit = AngularUnit.DMS,
    val correctionsDatum: CorrectionsDatum = CorrectionsDatum.ETRS89,
) {
    /** El formulario de proyecto nuevo, sin nombre ni descripción (S006/RF-6). */
    fun toDraft() = ProjectDraft("", author, "", crs, linearUnit, angularUnit, correctionsDatum)
}

/** Una carpeta de proyectos (S006/RF-17). */
data class ProjectFolder(val id: String, val name: String)

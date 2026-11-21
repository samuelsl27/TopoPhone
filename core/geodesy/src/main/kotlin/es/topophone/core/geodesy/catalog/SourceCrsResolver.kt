package es.topophone.core.geodesy.catalog

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.model.project.ProjectCrs

/** Qué da elegir el SC de origen de un fichero que se importa (S057/RF-2, RF-8, RF-10). */
sealed interface SourceCrsResolution {
    /** El SC elegido es el del proyecto: no hay nada que reproyectar ni ningún cambio de datum (S057/RF-12). */
    data object SameAsProject : SourceCrsResolution

    /** Otro SC de la biblioteca: lo que da construirlo con el datum de las correcciones del proyecto. */
    data class Other(val result: CrsBuildResult) : SourceCrsResolution
}

/**
 * Del código EPSG elegido como SC de origen y del proyecto al SC con su transformación hasta el datum de las correcciones del proyecto
 * (S057/RF-2, RF-8). Usa la misma elección de transformación que un proyecto (S034/RF-10): la más precisa cuyo ámbito cubre [centre].
 */
object SourceCrsResolver {
    fun resolve(
        catalog: CrsCatalog,
        epsg: Int,
        project: ProjectCrs,
        centre: GeoPoint?,
        isInstalled: (String) -> Boolean = { false },
    ): SourceCrsResolution = if (epsg == project.epsg) {
        SourceCrsResolution.SameAsProject
    } else {
        SourceCrsResolution.Other(catalog.build(epsg, project.correctionsDatum, centre, isInstalled = isInstalled))
    }
}

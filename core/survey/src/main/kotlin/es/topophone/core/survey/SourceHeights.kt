package es.topophone.core.survey

import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.LibraryCrs
import es.topophone.core.model.project.ProjectCrs

/** Si las cotas de un fichero (un ráster) se pueden pasar al sistema de alturas del proyecto (S057/RF-19, RF-20). */
sealed interface HeightConversion {
    /** Las cotas del fichero ya están en el sistema de alturas del proyecto: no hace falta geoide. */
    data object Same : HeightConversion

    /** Se pasan con el geoide del proyecto. */
    data object Available : HeightConversion

    /** El proyecto no tiene geoide (no eligió altitud ortométrica, S034/RF-14): las cotas no se pueden convertir. */
    data object NoProjectGeoid : HeightConversion

    /** El geoide del proyecto es la rejilla [gridName] y no está instalada: se ofrece descargarla (S034/RF-19). */
    data class MissingGeoid(val gridName: String) : HeightConversion
}

/** Qué pasa con las cotas de un ráster al importarlo y al consultarlo en un proyecto (S057/RF-19, RF-20). */
object SourceHeights {

    /**
     * Cómo pasan las cotas del fichero, en [fileHeights], al sistema [projectHeights] del proyecto [crs]. Los SC de España llevan siempre su
     * geoide EGM08-REDNAP (S008/RF-4); uno de la biblioteca usa el geoide que eligió el proyecto, que puede faltar o no estar instalado.
     */
    fun conversion(
        crs: ProjectCrs,
        projectHeights: HeightSystem,
        fileHeights: HeightSystem,
        resources: LibraryResources = LibraryResources.current,
    ): HeightConversion {
        if (projectHeights == fileHeights) return HeightConversion.Same
        if (crs !is LibraryCrs) return HeightConversion.Available
        if (crs.geoidConstantM != null) return HeightConversion.Available // S035/RF-5
        val grid = crs.geoidGrid ?: return HeightConversion.NoProjectGeoid
        return if (resources.geoid(grid) == null) HeightConversion.MissingGeoid(grid) else HeightConversion.Available
    }
}

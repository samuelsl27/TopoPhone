package es.topophone.core.survey

import es.topophone.core.geodesy.GeoPoint
import es.topophone.core.geodesy.catalog.BuildWarning
import es.topophone.core.geodesy.catalog.CrsBuildResult
import es.topophone.core.geodesy.catalog.CrsCatalog
import es.topophone.core.geodesy.catalog.GridEntry
import es.topophone.core.model.project.CrsDefinition
import es.topophone.core.model.project.GeoBox

/**
 * El cambio de datum con que se importa un fichero de otro SC de la biblioteca, para enseñarlo en la vista previa y pedir confirmación
 * (S057/RF-8, RF-9, RF-10): el nombre de la transformación de EPSG, su precisión y su ámbito, las rejillas que necesita y cuáles faltan.
 *
 * Es la transformación más precisa cuyo ámbito cubre la zona del fichero (S034/RF-10), la que ya va en la definición del SC de origen; aquí no se
 * deja elegir otra (S057, D4).
 */
data class ImportTransformation(
    val name: String,
    /** Precisión declarada por EPSG en metros, o `null` si no la declara. */
    val accuracyM: Double?,
    val area: GeoBox?,
    val gridNames: List<String>,
    /** Las rejillas de [gridNames] que no vienen en la app ni están instaladas: sin ellas no se importa y se ofrece descargarlas (S057/RF-10). */
    val missingGrids: List<GridEntry>,
    /** Ninguna transformación declara cubrir la zona del fichero: se usa la más precisa de todas (S034/RF-10). */
    val outsideArea: Boolean,
) {
    /** Hay que confirmar antes de importar: la precisión pasa de [CONFIRM_ABOVE_M] o no está declarada (S057/RF-9). */
    val needsConfirmation: Boolean get() = accuracyM == null || accuracyM > CONFIRM_ABOVE_M

    /** Falta alguna rejilla: la importación no empieza (S057/RF-10). */
    val needsGrids: Boolean get() = missingGrids.isNotEmpty()

    companion object {
        /** Por encima de esta precisión (m) el cambio de datum se confirma antes de importar (S057/RF-9). */
        const val CONFIRM_ABOVE_M = 1.0

        /** La transformación de [definition], o `null` si el datum del SC de origen coincide con el de las correcciones (sin paso que aplicar). */
        fun of(
            definition: CrsDefinition,
            missingGrids: List<GridEntry> = emptyList(),
            outsideArea: Boolean = false,
        ): ImportTransformation? = if (definition.transformationSteps == NULL_STEP) {
            null
        } else {
            ImportTransformation(
                name = definition.transformationName,
                accuracyM = definition.transformationAccuracyM,
                area = definition.transformationArea,
                gridNames = definition.gridNames,
                missingGrids = missingGrids,
                outsideArea = outsideArea,
            )
        }

        /**
         * La transformación de [definition] (un SC de origen ya construido) con lo que falta por instalar según [resources] y si su ámbito deja
         * fuera el [centre] de la zona del fichero; `null` si no hay cambio de datum (S057/RF-8, RF-10).
         */
        fun forSource(
            definition: CrsDefinition,
            centre: GeoPoint?,
            catalog: CrsCatalog = CrsCatalog.bundled,
            resources: LibraryResources = LibraryResources.current,
        ): ImportTransformation? {
            val missing = definition.gridNames.mapNotNull { catalog.grids[it] }.filterNot { it.bundled || resources.isInstalled(it.name) }
            val area = definition.transformationArea
            val outside = area != null && centre != null && !area.contains(centre.latitudeDeg, centre.longitudeDeg)
            return of(definition, missing, outside)
        }

        /** La transformación de lo que da elegir el SC de origen; `null` si no hay cambio de datum o el SC no se puede usar. */
        fun of(result: CrsBuildResult): ImportTransformation? = when (result) {
            is CrsBuildResult.Ready -> of(result.definition, emptyList(), BuildWarning.TRANSFORMATION_OUTSIDE_AREA in result.warnings)
            is CrsBuildResult.NeedsGrids ->
                of(result.definition, result.grids, BuildWarning.TRANSFORMATION_OUTSIDE_AREA in result.warnings)
            is CrsBuildResult.Unavailable -> null
        }

        private const val NULL_STEP = "null"
    }
}

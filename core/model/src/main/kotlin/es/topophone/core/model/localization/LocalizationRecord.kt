package es.topophone.core.model.localization

import es.topophone.core.model.project.HeightSystem
import es.topophone.core.model.project.ProjectCrs

/** El residuo guardado de un par en el último cálculo, en metros: conocido menos transformado; null en la componente que no entra (S039/RF-10). */
data class PairResidualValues(val east: Double?, val north: Double?, val vertical: Double?)

/** La localización activa de un proyecto, lista para aplicarla a una posición (S039/RF-17, plan D4). */
data class ActiveLocalizationInfo(val id: String, val params: LocalizationParams)

/**
 * Una localización guardada de un proyecto (S039/RF-13, RF-16, RF-21): el borrador, la activa o una archivada. [params] es null en un borrador sin
 * calcular; [residuals] y los RMS son los del cálculo con que se aplicó. [crsText] y [heightSystem] son el SC y las alturas del proyecto al crearla.
 */
data class Localization(
    val id: String,
    val projectId: String,
    val status: LocalizationStatus,
    val origin: LocalizationOrigin,
    val name: String?,
    val createdAtMs: Long,
    val appliedAtMs: Long?,
    val fixScale: Boolean,
    val residualLimitM: Double,
    val scaleLimit: Double,
    val crsText: String,
    val heightSystem: String,
    val params: LocalizationParams?,
    val rmsHorizontal: Double?,
    val rmsVertical: Double?,
    val pairs: List<LocalizationPair>,
    val residuals: Map<String, PairResidualValues> = emptyMap(),
)

/**
 * Un SC de «Mis SC» guardado con una localización (S039/RF-24, RF-28): el SC base y las alturas del proyecto con que se calculó y los parámetros de
 * la localización.
 */
data class LocalizedCrs(
    val id: String,
    val name: String,
    val crs: ProjectCrs,
    val heightSystem: HeightSystem,
    val params: LocalizationParams,
    val createdAtMs: Long,
)

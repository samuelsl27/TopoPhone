package es.topophone.data.alignment

import es.topophone.core.formats.landxml.LandXmlAlignmentReader
import es.topophone.core.formats.landxml.LandXmlAlignmentScan
import es.topophone.core.formats.landxml.LandXmlException
import es.topophone.core.formats.landxml.LandXmlProblem
import es.topophone.core.formats.xml.XmlFormatException
import es.topophone.core.model.alignment.AlignmentIssue
import es.topophone.core.model.alignment.AlignmentSource
import es.topophone.core.model.alignment.ProfileIssue
import es.topophone.core.model.alignment.ProfileOmission
import es.topophone.core.model.alignment.VerticalProfile
import es.topophone.core.model.alignment.VerticalVertex
import es.topophone.core.model.cad.CadSourceCrs
import es.topophone.core.model.project.LinearUnit
import es.topophone.core.model.project.ProjectCrs
import es.topophone.core.model.project.TerrainPlane
import es.topophone.core.survey.AlignmentDraft
import es.topophone.core.survey.AlignmentProjection
import es.topophone.core.survey.AlignmentProjector
import es.topophone.core.survey.ProfileValidator
import es.topophone.core.survey.TerrainView
import es.topophone.data.io.CountingInputStream
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Por qué no se importó o no se pudo leer un LandXML de ejes (S049/RF-3). */
enum class AlignmentImportFailure { NOT_LANDXML, UNSUPPORTED_VERSION, MALFORMED, NO_ALIGNMENT, READ_ERROR, WRITE_ERROR }

/** Lo que se ve de un LandXML antes de importar sus ejes (S049/RF-2, RF-3). */
sealed interface AlignmentScanResult {
    data class Found(val scan: LandXmlAlignmentScan) : AlignmentScanResult

    data class Failed(val reason: AlignmentImportFailure, val version: String? = null) : AlignmentScanResult
}

/** Un eje importado, para el resumen (S049/RF-13): lo que se guardó y, si el fichero traía rasante que no se usó, por qué. */
data class ImportedAlignment(
    val id: String,
    val name: String,
    val lengthM: Double,
    val startChainage: Double,
    val hasProfile: Boolean,
    val profileOmission: ProfileOmission?,
    val approximated: Boolean,
)

/** Un eje que no se importó y por qué (S049/RF-5, RF-12). */
data class IgnoredAlignment(val name: String, val issue: AlignmentIssue)

/** El resumen de una importación terminada (S049/RF-13): lo importado, lo ignorado y qué eje quedó activo, si el proyecto no tenía. */
data class AlignmentImportSummary(val imported: List<ImportedAlignment>, val ignored: List<IgnoredAlignment>, val activatedId: String?)

sealed interface AlignmentImportResult {
    data class Done(val summary: AlignmentImportSummary) : AlignmentImportResult

    data class Failed(val reason: AlignmentImportFailure, val version: String? = null) : AlignmentImportResult
}

/** Cómo acabó poner la rasante a un eje (S049/RF-9, RF-10). */
sealed interface ProfileAttachResult {
    data object Attached : ProfileAttachResult

    data object AxisNotFound : ProfileAttachResult

    data class Rejected(val issue: ProfileIssue) : ProfileAttachResult

    data object WriteError : ProfileAttachResult
}

/**
 * Importa ejes a un proyecto (S049/RF-2 a RF-13): los lee del LandXML (o recibe los que ya se armaron de una polilínea o a mano), los
 * pasa al sistema y a los metros del proyecto con [AlignmentProjector] y los guarda todos en una sola transacción. Cancelar o fallar la
 * escritura no deja nada guardado. Un eje que no vale se ignora y se cuenta en el resumen; los demás se importan.
 */
class AlignmentImporter(private val repository: AlignmentRepository, private val clock: () -> Long = System::currentTimeMillis) {
    /** La lista de ejes del fichero y su unidad: una pasada que no guarda nada (RF-2, RF-3). */
    suspend fun scan(openStream: () -> InputStream, onProgress: (Long) -> Unit = {}): AlignmentScanResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        try {
            AlignmentScanResult.Found(
                CountingInputStream(openStream(), onProgress, job::ensureActive).use {
                    LandXmlAlignmentReader.scan(it)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: LandXmlException) {
            AlignmentScanResult.Failed(e.problem.toFailure(), e.version)
        } catch (e: XmlFormatException) {
            AlignmentScanResult.Failed(AlignmentImportFailure.MALFORMED)
        } catch (e: Exception) {
            AlignmentScanResult.Failed(AlignmentImportFailure.READ_ERROR)
        }
    }

    /**
     * Importa las alineaciones de [indices] (las `index` de [scan]) del fichero [fileName]. [onProgress] recibe los bytes leídos.
     * Lanza `CancellationException` si se cancela; en ese caso, y si falla la escritura, no queda nada guardado.
     */
    suspend fun importLandXml(
        projectId: String,
        crs: ProjectCrs,
        fileName: String,
        indices: Set<Int>,
        source: CadSourceCrs,
        unit: LinearUnit,
        openStream: () -> InputStream,
        /** S058/RF-17: el plano del proyecto en terreno; el eje «del proyecto» del fichero es de terreno y se guarda en cuadrícula. */
        terrain: TerrainPlane? = null,
        onProgress: (Long) -> Unit = {},
    ): AlignmentImportResult = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        try {
            val reads = CountingInputStream(openStream(), onProgress, job::ensureActive).use { LandXmlAlignmentReader.read(it, indices) }
            job.ensureActive()
            if (reads.isEmpty()) return@withContext AlignmentImportResult.Failed(AlignmentImportFailure.NO_ALIGNMENT)
            val projector = AlignmentProjector(crs, source, unit, terrain = TerrainView.of(terrain, crs) ?: TerrainView.GRID)
            val toSave = mutableListOf<Pending>()
            val ignored = mutableListOf<IgnoredAlignment>()
            for (read in reads) {
                val name = read.name.ifBlank { fileName.substringBeforeLast('.') }
                read.issue?.let { ignored += IgnoredAlignment(name, it) } ?: run {
                    when (
                        val projection = projector.convert(
                            AlignmentDraft(read.elements, read.declaredEnds, read.profile, read.profileOmission),
                        )
                    ) {
                        is AlignmentProjection.Rejected -> ignored += IgnoredAlignment(name, projection.issue)
                        is AlignmentProjection.Converted -> toSave += projection.pending(name, fileName, AlignmentSource.LANDXML)
                    }
                }
            }
            job.ensureActive()
            save(projectId, toSave, ignored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: LandXmlException) {
            AlignmentImportResult.Failed(e.problem.toFailure(), e.version)
        } catch (e: XmlFormatException) {
            AlignmentImportResult.Failed(AlignmentImportFailure.MALFORMED)
        } catch (e: Exception) {
            AlignmentImportResult.Failed(AlignmentImportFailure.READ_ERROR)
        }
    }

    /**
     * Importa un eje que no viene de un LandXML: el de una polilínea de un dibujo o el escrito a mano (RF-6 a RF-8). El [draft] está en
     * [unit] y en [source].
     */
    suspend fun importDraft(
        projectId: String,
        crs: ProjectCrs,
        name: String,
        fileName: String?,
        origin: AlignmentSource,
        draft: AlignmentDraft,
        source: CadSourceCrs = CadSourceCrs.Project,
        unit: LinearUnit = LinearUnit.METRE,
    ): AlignmentImportResult = withContext(Dispatchers.IO) {
        try {
            when (val projection = AlignmentProjector(crs, source, unit).convert(draft)) {
                is AlignmentProjection.Rejected -> AlignmentImportResult.Done(
                    AlignmentImportSummary(emptyList(), listOf(IgnoredAlignment(name, projection.issue)), null),
                )
                is AlignmentProjection.Converted -> save(projectId, listOf(projection.pending(name, fileName, origin)), emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AlignmentImportResult.Failed(AlignmentImportFailure.READ_ERROR)
        }
    }

    /**
     * Asocia una rasante a un eje ya importado (RF-9): la valida contra el PK del eje (RF-10) y la guarda. Los vértices ya están en
     * metros y en los PK del eje.
     */
    suspend fun attachProfile(alignmentId: String, vertices: List<VerticalVertex>): ProfileAttachResult = withContext(Dispatchers.IO) {
        val alignment = repository.alignment(alignmentId) ?: return@withContext ProfileAttachResult.AxisNotFound
        ProfileValidator.validate(vertices, alignment.startChainage, alignment.endChainage)?.let {
            return@withContext ProfileAttachResult.Rejected(it)
        }
        try {
            if (repository.setProfile(alignmentId, VerticalProfile(vertices.sortedBy { it.chainage }))) {
                ProfileAttachResult.Attached
            } else {
                ProfileAttachResult.AxisNotFound
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProfileAttachResult.WriteError
        }
    }

    private suspend fun save(projectId: String, toSave: List<Pending>, ignored: List<IgnoredAlignment>): AlignmentImportResult {
        if (toSave.isEmpty()) return AlignmentImportResult.Done(AlignmentImportSummary(emptyList(), ignored, null))
        val added = try {
            repository.add(projectId, toSave.map { it.item }, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return AlignmentImportResult.Failed(AlignmentImportFailure.WRITE_ERROR)
        }
        val imported = toSave.zip(added.ids).map { (pending, id) ->
            val item = pending.item
            ImportedAlignment(
                id = id,
                name = item.name.trim(),
                lengthM = item.elements.sumOf { it.length },
                startChainage = item.elements.first().startChainage,
                hasProfile = item.profile != null,
                profileOmission = pending.omission,
                approximated = item.approximated,
            )
        }
        return AlignmentImportResult.Done(AlignmentImportSummary(imported, ignored, added.activatedId))
    }

    /** Un eje listo para guardar y el motivo por el que se le omitió la rasante del fichero, para el resumen. */
    private class Pending(val item: AlignmentToSave, val omission: ProfileOmission?)

    private fun AlignmentProjection.Converted.pending(name: String, fileName: String?, source: AlignmentSource) = Pending(
        AlignmentToSave(
            name = name,
            fileName = fileName,
            source = source,
            elements = elements,
            profile = profile?.let { VerticalProfile(it) },
            extent = extent,
            approximated = approximated,
        ),
        profileOmission,
    )

    private fun LandXmlProblem.toFailure() = when (this) {
        LandXmlProblem.NOT_LANDXML -> AlignmentImportFailure.NOT_LANDXML
        LandXmlProblem.UNSUPPORTED_VERSION -> AlignmentImportFailure.UNSUPPORTED_VERSION
    }
}

package es.topophone.data.section

import androidx.room.withTransaction
import es.topophone.core.model.alignment.section.CrossSection
import es.topophone.core.model.alignment.section.HeightKind
import es.topophone.core.model.alignment.section.SectionCode
import es.topophone.core.model.alignment.section.SectionCodec
import es.topophone.core.model.alignment.section.SectionIssue
import es.topophone.core.model.alignment.section.SectionKind
import es.topophone.core.model.alignment.section.SectionTemplate
import es.topophone.core.model.alignment.section.SuperelevationRow
import es.topophone.core.model.log.AppLog
import es.topophone.core.model.log.LogCategory
import es.topophone.core.survey.SectionValidator
import es.topophone.core.survey.SuperelevationValidator
import es.topophone.data.db.TopoPhoneDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Una sección tipo guardada, con su identificador (para borrarla) y su fecha (S050/RF-8). */
data class StoredTemplate(val id: String, val template: SectionTemplate, val createdAtMs: Long)

/** Una sección explícita guardada, de diseño o de terreno (S050/RF-8). */
data class StoredSection(val id: String, val section: CrossSection, val createdAtMs: Long)

/** Cómo acabó guardar secciones, secciones tipo o peralte (S050/RF-7, RF-8). */
sealed interface SectionSaveResult {
    /** Se guardó todo; [ids] son los identificadores en el orden dado (vacío para el peralte, que se guarda entero). */
    data class Saved(val ids: List<String>) : SectionSaveResult

    /** No se guardó nada: el motivo y el número, desde 0, del elemento de la lista que no valía. */
    data class Rejected(val issue: SectionIssue, val index: Int) : SectionSaveResult

    data object AxisNotFound : SectionSaveResult
}

/**
 * Las secciones transversales y los peraltes de los ejes (S050). Cada alta y cada baja es una transacción única con durabilidad
 * completa (RNF-T6); se valida antes de guardar y, si algo no vale, no se guarda nada (RF-7). Se borran con el eje y no tocan ningún
 * punto (RF-8). Una sección explícita nueva en el mismo PK y tipo que otra la sustituye.
 */
interface SectionRepository {
    fun templates(alignmentId: String): Flow<List<StoredTemplate>>

    fun sections(alignmentId: String): Flow<List<StoredSection>>

    fun superelevation(alignmentId: String): Flow<List<SuperelevationRow>>

    suspend fun addTemplate(alignmentId: String, template: SectionTemplate): SectionSaveResult

    suspend fun addSections(alignmentId: String, sections: List<CrossSection>): SectionSaveResult

    /** Sustituye la tabla de peralte del eje por [rows] (vacía la deja sin peralte). */
    suspend fun setSuperelevation(alignmentId: String, rows: List<SuperelevationRow>): SectionSaveResult

    suspend fun deleteTemplate(id: String)

    suspend fun deleteSection(id: String)
}

class RoomSectionRepository(
    private val database: TopoPhoneDatabase,
    private val log: AppLog = AppLog.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : SectionRepository {
    private val dao = database.sections()
    private val alignmentDao = database.alignments()

    override fun templates(alignmentId: String): Flow<List<StoredTemplate>> = dao.templates(alignmentId).map { rows ->
        rows.map { StoredTemplate(it.id, it.toModel(), it.createdAtMs) }
    }

    override fun sections(alignmentId: String): Flow<List<StoredSection>> = dao.profiles(alignmentId).map { rows ->
        rows.map { StoredSection(it.id, it.toModel(), it.createdAtMs) }
    }

    override fun superelevation(alignmentId: String): Flow<List<SuperelevationRow>> = dao.rows(alignmentId).map { rows ->
        rows.map { SuperelevationRow(it.chainage, it.leftSlope, it.rightSlope) }
    }

    override suspend fun addTemplate(alignmentId: String, template: SectionTemplate): SectionSaveResult = database.withTransaction {
        val axis = alignmentDao.byId(alignmentId) ?: return@withTransaction SectionSaveResult.AxisNotFound
        val others = dao.templatesNow(alignmentId).map { it.toModel() }
        SectionValidator.validate(template, axis.startChainage, axis.startChainage + axis.lengthM, others)?.let {
            return@withTransaction SectionSaveResult.Rejected(it, 0)
        }
        val id = newId()
        dao.insertTemplate(
            SectionTemplateEntity(
                id,
                alignmentId,
                template.fromChainage,
                template.toChainage,
                template.gradeCode.takeIf { it != SectionCode.AXIS },
                SectionCodec.encodeTemplate(template),
                clock(),
            ),
        )
        log.info(LogCategory.APP, "Guardada 1 sección tipo")
        SectionSaveResult.Saved(listOf(id))
    }

    override suspend fun addSections(alignmentId: String, sections: List<CrossSection>): SectionSaveResult = database.withTransaction {
        val axis = alignmentDao.byId(alignmentId) ?: return@withTransaction SectionSaveResult.AxisNotFound
        val end = axis.startChainage + axis.lengthM
        sections.forEachIndexed { i, s ->
            SectionValidator.validate(s, axis.startChainage, end)?.let { return@withTransaction SectionSaveResult.Rejected(it, i) }
        }
        val existing = dao.profilesNow(alignmentId)
        val now = clock()
        val ids = sections.map { s ->
            existing.filter { it.kind == s.kind.name && it.chainage == s.chainage }.forEach { dao.deleteProfile(it.id) }
            val id = newId()
            dao.insertProfile(
                SectionProfileEntity(id, alignmentId, s.kind.name, s.chainage, s.heightKind.name, SectionCodec.encodePoints(s.points), now),
            )
            id
        }
        if (ids.isNotEmpty()) log.info(LogCategory.APP, "Guardadas ${ids.size} secciones")
        SectionSaveResult.Saved(ids)
    }

    override suspend fun setSuperelevation(alignmentId: String, rows: List<SuperelevationRow>): SectionSaveResult =
        database.withTransaction {
            val axis = alignmentDao.byId(alignmentId) ?: return@withTransaction SectionSaveResult.AxisNotFound
            SuperelevationValidator.validate(rows, axis.startChainage, axis.startChainage + axis.lengthM)?.let {
                return@withTransaction SectionSaveResult.Rejected(it, 0)
            }
            dao.rowsNow(alignmentId).forEach { dao.deleteRow(it.id) }
            val now = clock()
            rows.forEach { dao.insertRow(SuperelevationRowEntity(newId(), alignmentId, it.chainage, it.leftSlope, it.rightSlope, now)) }
            log.info(LogCategory.APP, "Guardado un peralte de ${rows.size} filas")
            SectionSaveResult.Saved(emptyList())
        }

    override suspend fun deleteTemplate(id: String) {
        dao.deleteTemplate(id)
        log.info(LogCategory.APP, "Borrada 1 sección tipo")
    }

    override suspend fun deleteSection(id: String) {
        dao.deleteProfile(id)
        log.info(LogCategory.APP, "Borrada 1 sección")
    }

    private fun SectionTemplateEntity.toModel(): SectionTemplate = SectionCodec.decodeTemplate(geometry)

    private fun SectionProfileEntity.toModel(): CrossSection = CrossSection(
        SectionKind.valueOf(kind),
        chainage,
        HeightKind.valueOf(heightKind),
        SectionCodec.decodePoints(points),
    )
}

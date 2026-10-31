package es.topophone.data.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import java.io.File
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** De dónde se leen las fotos de una copia automática al restaurarla (S017b/RF-32). */
fun interface ExternalPhotos {
    /** El contenido de la foto [name] (`<id>.jpg`) o null si falta o no se puede leer. */
    suspend fun open(name: String): InputStream?
}

/** La carpeta de las copias automáticas, elegida por el usuario con el selector de carpetas (S013/RF-17; RNF-T5). */
interface BackupFolder {
    /** Guarda en la carpeta [treeUri] el fichero [from] con el nombre [name]; false si no se ha podido, sin dejarlo a medias. */
    suspend fun write(treeUri: String, name: String, from: File): Boolean

    /** Nombres de los ficheros de la carpeta; null si la carpeta ya no está o no hay permiso. */
    suspend fun list(treeUri: String): List<String>?

    suspend fun delete(treeUri: String, name: String): Boolean

    /**
     * Las fotos de las copias automáticas viven en una subcarpeta propia, `TopoPhone_fotos` (S017b/RF-30). [writePhoto] la crea la
     * primera vez, guarda [from] como [name] (`<id>.jpg`) sin dejarlo a medias y no repite una foto que ya está; false si no se ha podido.
     */
    suspend fun writePhoto(treeUri: String, name: String, from: File): Boolean

    /** Nombres de las fotos de la subcarpeta; lista vacía si aún no existe y null si la carpeta no se puede leer (S017b/RF-31). */
    suspend fun listPhotos(treeUri: String): List<String>?

    /** Borra una foto de la subcarpeta; los demás ficheros de la carpeta no se tocan (S017b/RF-31). */
    suspend fun deletePhoto(treeUri: String, name: String): Boolean

    /** Abre una foto de la subcarpeta; null si falta o el proveedor falla (S017b/RF-32). */
    suspend fun openPhoto(treeUri: String, name: String): InputStream?

    /** Las fotos de la subcarpeta de [treeUri], para restaurar una copia automática (S017b/RF-32). */
    fun externalPhotos(treeUri: String): ExternalPhotos = ExternalPhotos { openPhoto(treeUri, it) }

    /** Guarda el permiso sobre la carpeta para que siga valiendo al reiniciar el móvil. */
    fun persist(treeUri: String)

    fun release(treeUri: String)
}

/**
 * [BackupFolder] con `DocumentsContract`, sin dependencias nuevas (plan, decisiones técnicas). Cualquier fallo del
 * proveedor (carpeta borrada, tarjeta retirada, permiso revocado, sin espacio) es un "no se ha podido" (S013/RF-21).
 */
class DocumentsBackupFolder(private val resolver: ContentResolver) : BackupFolder {

    override suspend fun write(treeUri: String, name: String, from: File): Boolean = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val document = attempt { DocumentsContract.createDocument(resolver, folder(tree), MIME_ZIP, name) } ?: return@withContext false
        val written = attempt {
            val out = resolver.openOutputStream(document, "w") ?: error("Sin salida para $document")
            out.use { stream -> from.inputStream().use { it.copyTo(stream) } }
        }
        if (written == null) attempt { DocumentsContract.deleteDocument(resolver, document) } // S013/RF-5: nada a medias.
        written != null
    }

    override suspend fun list(treeUri: String): List<String>? = withContext(Dispatchers.IO) { children(Uri.parse(treeUri))?.keys?.toList() }

    override suspend fun writePhoto(treeUri: String, name: String, from: File): Boolean = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val sub = photosFolder(tree, create = true) ?: return@withContext false
        val existing = children(tree, subId = DocumentsContract.getDocumentId(sub)) ?: return@withContext false
        if (name in existing) return@withContext true
        val document = attempt { DocumentsContract.createDocument(resolver, sub, MIME_JPEG, name) } ?: return@withContext false
        val written = attempt {
            val out = resolver.openOutputStream(document, "w") ?: error("Sin salida para $document")
            out.use { stream -> from.inputStream().use { it.copyTo(stream) } }
        }
        if (written == null) attempt { DocumentsContract.deleteDocument(resolver, document) } // Nada a medias (S013/RF-5).
        written != null
    }

    override suspend fun listPhotos(treeUri: String): List<String>? = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val root = children(tree) ?: return@withContext null
        val sub = root[PHOTOS_FOLDER] ?: return@withContext emptyList()
        children(tree, subId = sub)?.keys?.toList()
    }

    override suspend fun deletePhoto(treeUri: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val sub = children(tree)?.get(PHOTOS_FOLDER) ?: return@withContext false
        val id = children(tree, subId = sub)?.get(name) ?: return@withContext false
        attempt { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } == true
    }

    override suspend fun openPhoto(treeUri: String, name: String): InputStream? = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val sub = children(tree)?.get(PHOTOS_FOLDER) ?: return@withContext null
        val id = children(tree, subId = sub)?.get(name) ?: return@withContext null
        attempt { resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
    }

    /** La subcarpeta de fotos; con [create], la crea si no existe. null si no se puede. */
    private fun photosFolder(tree: Uri, create: Boolean): Uri? {
        val id = (children(tree) ?: return null)[PHOTOS_FOLDER]
        if (id != null) return DocumentsContract.buildDocumentUriUsingTree(tree, id)
        if (!create) return null
        return attempt { DocumentsContract.createDocument(resolver, folder(tree), DocumentsContract.Document.MIME_TYPE_DIR, PHOTOS_FOLDER) }
    }

    override suspend fun delete(treeUri: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val tree = Uri.parse(treeUri)
        val id = children(tree)?.get(name) ?: return@withContext false
        attempt { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) } == true
    }

    override fun persist(treeUri: String) {
        resolver.takePersistableUriPermission(Uri.parse(treeUri), FLAGS)
    }

    override fun release(treeUri: String) {
        attempt { resolver.releasePersistableUriPermission(Uri.parse(treeUri), FLAGS) }
    }

    private fun folder(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** Nombre → id de documento de los hijos de la carpeta (o de su subcarpeta [subId]); null si no se puede leer. */
    private fun children(tree: Uri, subId: String = DocumentsContract.getTreeDocumentId(tree)): Map<String, String>? = attempt {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, subId)
        // La consulta con Bundle (API 26) es la que entienden los proveedores de documentos.
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null as Bundle?,
            null,
        )
            ?.use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(1), cursor.getString(0)) } }
    }

    /** null si falla; los proveedores lanzan de todo (FileNotFound, Security, IllegalArgument, IllegalState…). */
    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val MIME_ZIP = "application/zip"
        const val MIME_JPEG = "image/jpeg"

        /** La subcarpeta de las fotos de las copias automáticas (S017b/RF-30). */
        const val PHOTOS_FOLDER = "TopoPhone_fotos"
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

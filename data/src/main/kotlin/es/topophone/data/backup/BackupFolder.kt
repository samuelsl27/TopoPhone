package es.topophone.data.backup

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** La carpeta de las copias automáticas, elegida por el usuario con el selector de carpetas (S013/RF-17; RNF-T5). */
interface BackupFolder {
    /** Guarda en la carpeta [treeUri] el fichero [from] con el nombre [name]; false si no se ha podido, sin dejarlo a medias. */
    suspend fun write(treeUri: String, name: String, from: File): Boolean

    /** Nombres de los ficheros de la carpeta; null si la carpeta ya no está o no hay permiso. */
    suspend fun list(treeUri: String): List<String>?

    suspend fun delete(treeUri: String, name: String): Boolean

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

    /** Nombre → id de documento de los hijos de la carpeta; null si no se puede leer. */
    private fun children(tree: Uri): Map<String, String>? = attempt {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
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
        const val FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

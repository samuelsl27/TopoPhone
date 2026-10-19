package es.topophone.data.backup

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * Proveedor de documentos de prueba sobre una carpeta: hace de "carpeta elegida por el usuario" (SAF) en los tests de
 * las copias automáticas (S013/RF-17, RF-19, RF-21). Los ids de documento son rutas relativas a [root]; la raíz es "root".
 */
class FolderDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID)).apply { addRow(arrayOf<Any>(ROOT, ROOT)) }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DEFAULT).apply { add(this, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = file(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException(parentDocumentId)
        return MatrixCursor(projection ?: DEFAULT).apply {
            dir.listFiles().orEmpty().sortedBy { it.name }.forEach { add(this, "$parentDocumentId/${it.name}") }
        }
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (failWrites && mode.contains('w')) {
            // Se abre, pero el disco se llena a mitad: el fichero queda a medias.
            file(documentId).writeText("a medias")
            throw FileNotFoundException("sin espacio")
        }
        return ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = file(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException(parentDocumentId)
        var name = displayName
        var n = 1
        while (File(dir, name).exists()) name = displayName.replace(".zip", " (${n++}).zip")
        File(dir, name).createNewFile()
        return "$parentDocumentId/$name"
    }

    override fun deleteDocument(documentId: String) {
        if (!file(documentId).delete()) throw FileNotFoundException(documentId)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = documentId.startsWith("$parentDocumentId/")

    private fun file(documentId: String): File = if (documentId == ROOT) root else File(root, documentId.removePrefix("$ROOT/"))

    private fun add(cursor: MatrixCursor, documentId: String) {
        val file = file(documentId)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, file.name)
            add(Document.COLUMN_MIME_TYPE, if (file.isDirectory) Document.MIME_TYPE_DIR else "application/zip")
            add(Document.COLUMN_SIZE, file.length())
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_WRITE or Document.FLAG_DIR_SUPPORTS_CREATE)
        }
    }

    companion object {
        const val AUTHORITY = "es.topophone.test.documents"
        const val ROOT = "root"
        lateinit var root: File
        var failWrites = false
        private val DEFAULT = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}

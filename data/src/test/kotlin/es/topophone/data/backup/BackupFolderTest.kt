package es.topophone.data.backup

import android.Manifest
import android.content.Context
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** La carpeta de las copias automáticas, elegida con el selector de carpetas (S013/RF-5, RF-17, RF-19; RNF-T5). */
@RunWith(AndroidJUnit4::class)
class BackupFolderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tree = DocumentsContract.buildTreeDocumentUri(FolderDocumentsProvider.AUTHORITY, FolderDocumentsProvider.ROOT).toString()
    private lateinit var dir: File
    private lateinit var backupFolder: DocumentsBackupFolder

    @Before
    fun open() {
        dir = folder.newFolder("copias")
        FolderDocumentsProvider.root = dir
        FolderDocumentsProvider.failWrites = false
        val info = ProviderInfo().apply {
            authority = FolderDocumentsProvider.AUTHORITY
            exported = true
            grantUriPermissions = true
            readPermission = Manifest.permission.MANAGE_DOCUMENTS
            writePermission = Manifest.permission.MANAGE_DOCUMENTS
        }
        Robolectric.buildContentProvider(FolderDocumentsProvider::class.java).create(info)
        backupFolder = DocumentsBackupFolder(context.contentResolver)
    }

    @After
    fun close() {
        FolderDocumentsProvider.failWrites = false
    }

    private fun source(text: String) = File(folder.root, "copia-${System.nanoTime()}.zip").apply { writeText(text) }

    @Test
    fun `S013 RF-17 la copia se escribe en la carpeta elegida`() {
        runBlocking { backupFolder.write(tree, "TopoPhone_auto_20261019-0802.zip", source("zip")) } shouldBe true

        File(dir, "TopoPhone_auto_20261019-0802.zip").readText() shouldBe "zip"
    }

    @Test
    fun `S013 RF-19 se listan los ficheros de la carpeta`() {
        File(dir, "notas.txt").writeText("x")
        runBlocking { backupFolder.write(tree, "TopoPhone_auto_20261019-0802.zip", source("zip")) }

        runBlocking { backupFolder.list(tree) }?.toSet() shouldBe setOf("notas.txt", "TopoPhone_auto_20261019-0802.zip")
    }

    @Test
    fun `S013 RF-19 se borra una copia por su nombre`() {
        File(dir, "TopoPhone_auto_20261001-0800.zip").writeText("vieja")
        File(dir, "notas.txt").writeText("x")

        runBlocking { backupFolder.delete(tree, "TopoPhone_auto_20261001-0800.zip") } shouldBe true

        dir.list()!!.toSet() shouldBe setOf("notas.txt")
    }

    @Test
    fun `S013 RF-5 si no se puede escribir no queda un fichero a medias`() {
        FolderDocumentsProvider.failWrites = true

        runBlocking { backupFolder.write(tree, "TopoPhone_auto_20261019-0802.zip", source("zip")) } shouldBe false

        dir.list()!!.toList() shouldBe emptyList()
    }

    @Test
    fun `S013 RF-21 una carpeta que ya no existe da un fallo, no un cierre`() {
        dir.deleteRecursively()

        runBlocking { backupFolder.write(tree, "TopoPhone_auto_20261019-0802.zip", source("zip")) } shouldBe false
        runBlocking { backupFolder.list(tree) } shouldBe null
    }
}

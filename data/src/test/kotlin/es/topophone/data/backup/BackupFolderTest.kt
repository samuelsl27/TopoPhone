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

    private fun photoFile(text: String) = File(folder.root, "foto-${System.nanoTime()}.jpg").apply { writeText(text) }

    @Test
    fun `S017b RF-30 la foto se guarda en la subcarpeta de fotos, que se crea la primera vez`() {
        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("uno")) } shouldBe true
        runBlocking { backupFolder.writePhoto(tree, "bbb-222.jpg", photoFile("dos")) } shouldBe true

        File(dir, "TopoPhone_fotos/aaa-111.jpg").readText() shouldBe "uno"
        File(dir, "TopoPhone_fotos/bbb-222.jpg").readText() shouldBe "dos"
        dir.list()!!.toList() shouldBe listOf("TopoPhone_fotos")
    }

    @Test
    fun `S017b RF-30 una foto que ya esta no se repite ni se duplica`() {
        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("uno")) } shouldBe true
        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("otra")) } shouldBe true

        File(dir, "TopoPhone_fotos").list()!!.toList() shouldBe listOf("aaa-111.jpg")
        File(dir, "TopoPhone_fotos/aaa-111.jpg").readText() shouldBe "uno"
    }

    @Test
    fun `S017b RF-31 se listan las fotos y sin subcarpeta la lista esta vacia`() {
        runBlocking { backupFolder.listPhotos(tree) } shouldBe emptyList()

        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("uno")) }
        File(dir, "TopoPhone_auto_20261019-0802.zip").writeText("zip")

        runBlocking { backupFolder.listPhotos(tree) } shouldBe listOf("aaa-111.jpg")
    }

    @Test
    fun `S017b RF-31 borrar una foto no toca los demas ficheros de la carpeta`() {
        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("uno")) }
        runBlocking { backupFolder.writePhoto(tree, "bbb-222.jpg", photoFile("dos")) }
        File(dir, "TopoPhone_auto_20261019-0802.zip").writeText("zip")
        File(dir, "notas.txt").writeText("x")

        runBlocking { backupFolder.deletePhoto(tree, "aaa-111.jpg") } shouldBe true
        runBlocking { backupFolder.deletePhoto(tree, "zzz.jpg") } shouldBe false
        runBlocking { backupFolder.deletePhoto(tree, "notas.txt") } shouldBe false

        File(dir, "TopoPhone_fotos").list()!!.toList() shouldBe listOf("bbb-222.jpg")
        dir.list()!!.toSet() shouldBe setOf("TopoPhone_fotos", "TopoPhone_auto_20261019-0802.zip", "notas.txt")
    }

    @Test
    fun `S017b RF-32 una foto se abre y las que faltan dan null`() {
        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("uno")) }

        runBlocking { backupFolder.openPhoto(tree, "aaa-111.jpg") }!!.use { it.readBytes().decodeToString() } shouldBe "uno"
        runBlocking { backupFolder.openPhoto(tree, "nada.jpg") } shouldBe null
        runBlocking { backupFolder.externalPhotos(tree).open("aaa-111.jpg") }!!.use { it.readBytes().decodeToString() } shouldBe "uno"
    }

    @Test
    fun `S017b RF-30 si no se puede escribir la foto no queda a medias`() {
        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("uno")) }
        FolderDocumentsProvider.failWrites = true

        runBlocking { backupFolder.writePhoto(tree, "bbb-222.jpg", photoFile("dos")) } shouldBe false

        File(dir, "TopoPhone_fotos").list()!!.toList() shouldBe listOf("aaa-111.jpg")
    }

    @Test
    fun `S017b RF-30 una carpeta que ya no existe da un fallo en las fotos, no un cierre`() {
        dir.deleteRecursively()

        runBlocking { backupFolder.writePhoto(tree, "aaa-111.jpg", photoFile("uno")) } shouldBe false
        runBlocking { backupFolder.listPhotos(tree) } shouldBe null
        runBlocking { backupFolder.deletePhoto(tree, "aaa-111.jpg") } shouldBe false
        runBlocking { backupFolder.openPhoto(tree, "aaa-111.jpg") } shouldBe null
    }
}

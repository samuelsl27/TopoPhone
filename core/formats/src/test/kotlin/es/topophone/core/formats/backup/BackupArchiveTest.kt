package es.topophone.core.formats.backup

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Fichero de la copia completa y del paquete: escribir, leer y rechazar lo que no vale (S013/RF-12, RF-13). */
class BackupArchiveTest {

    @TempDir
    lateinit var dir: File

    private val backup = BackupManifest(
        kind = BackupKind.BACKUP,
        schemaVersion = 6,
        appVersion = "0.13.0",
        createdAt = Instant.parse("2026-10-19T06:02:31.734Z"),
        projectCount = 4,
        pointCount = 9,
    )

    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }

    private fun files() = mapOf(
        BackupArchive.DATABASE to file("db", "SQLite format 3"),
        BackupArchive.SETTINGS to file("settings", """{"settings":{}}"""),
    )

    private fun written(manifest: BackupManifest = backup, files: Map<String, File> = files()): ByteArray =
        ByteArrayOutputStream().also { BackupArchive.write(manifest, files, it) }.toByteArray()

    private fun read(bytes: ByteArray, expected: BackupKind = BackupKind.BACKUP, schemaVersion: Int = 6): BackupReadResult =
        BackupArchive.read(bytes.inputStream(), expected, schemaVersion, File(dir, "out-${System.nanoTime()}"))

    /** Un ZIP con las entradas dadas, para fabricar ficheros dañados o ajenos. */
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, data) ->
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
    }.toByteArray()

    private fun entries(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(bytes.inputStream()).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                add(entry.name to input.readBytes())
            }
        }
    }

    @Test
    fun `S013 RF-12 una copia escrita se lee entera, con su manifiesto y sus sumas`() {
        val result = read(written())

        val valid = result.shouldBeInstanceOf<BackupReadResult.Valid>()
        valid.manifest.kind shouldBe BackupKind.BACKUP
        valid.manifest.createdAt shouldBe Instant.parse("2026-10-19T06:02:31Z")
        valid.manifest.projectCount shouldBe 4
        valid.manifest.pointCount shouldBe 9
        valid.manifest.appVersion shouldBe "0.13.0"
        valid.manifest.files.keys shouldBe setOf(BackupArchive.DATABASE, BackupArchive.SETTINGS)
        valid.files.getValue(BackupArchive.DATABASE).readText() shouldBe "SQLite format 3"
        valid.files.getValue(BackupArchive.SETTINGS).readText() shouldBe """{"settings":{}}"""
    }

    @Test
    fun `S013 RF-12 el manifiesto va primero y lleva el formato, la version y las sumas SHA-256`() {
        val list = entries(written())

        list.map { it.first } shouldBe listOf(BackupArchive.MANIFEST, BackupArchive.DATABASE, BackupArchive.SETTINGS)
        val manifest = list.first().second.decodeToString()
        manifest.contains("\"format\":\"topophone\"") shouldBe true
        manifest.contains("\"formatVersion\":1") shouldBe true
        manifest.contains("\"kind\":\"backup\"") shouldBe true
        manifest.contains("\"createdAt\":\"2026-10-19T06:02:31Z\"") shouldBe true
        // SHA-256 de "SQLite format 3".
        manifest.contains("\"data.db\":\"${sha256("SQLite format 3")}\"") shouldBe true
    }

    @Test
    fun `S013 RF-12 un paquete lleva el proyecto y se lee como paquete`() {
        val manifest = backup.copy(kind = BackupKind.PACKAGE, projectCount = 1, projectId = "p1", projectName = "Calle Mayor 12")
        val bytes = written(manifest, mapOf(BackupArchive.DATABASE to file("db", "SQLite format 3")))

        val valid = read(bytes, expected = BackupKind.PACKAGE).shouldBeInstanceOf<BackupReadResult.Valid>()
        valid.manifest.projectId shouldBe "p1"
        valid.manifest.projectName shouldBe "Calle Mayor 12"
    }

    @Test
    fun `S013 RF-12 un paquete elegido para restaurar, o una copia para importar, se rechaza`() {
        val pkg = written(
            backup.copy(kind = BackupKind.PACKAGE, projectId = "p1", projectName = "P"),
            mapOf(
                BackupArchive.DATABASE to file("db", "x"),
            ),
        )

        read(pkg, expected = BackupKind.BACKUP) shouldBe BackupReadResult.WrongKind(BackupKind.PACKAGE)
        read(written(), expected = BackupKind.PACKAGE) shouldBe BackupReadResult.WrongKind(BackupKind.BACKUP)
    }

    @Test
    fun `S013 RF-12 un fichero que no es de TopoPhone se rechaza`() {
        read("Name,Easting\r\n1,2\r\n".toByteArray()) shouldBe BackupReadResult.NotTopoPhone
        read(zip("foto.jpg" to byteArrayOf(1, 2, 3))) shouldBe BackupReadResult.NotTopoPhone
        read(zip(BackupArchive.MANIFEST to """{"format":"otra-app","formatVersion":1}""".toByteArray())) shouldBe
            BackupReadResult.NotTopoPhone
    }

    @Test
    fun `S013 RF-12 una copia con una suma que no coincide, sin una entrada o cortada esta danada`() {
        val good = entries(written())
        val changed = good.map { (name, data) ->
            if (name ==
                BackupArchive.DATABASE
            ) {
                name to "SQLite format 4".toByteArray()
            } else {
                name to data
            }
        }
        read(zip(*changed.toTypedArray())) shouldBe BackupReadResult.Damaged

        val missing = good.filter { it.first != BackupArchive.SETTINGS }
        read(zip(*missing.toTypedArray())) shouldBe BackupReadResult.Damaged

        val bytes = written()
        read(bytes.copyOf(bytes.size / 2)) shouldBe BackupReadResult.Damaged

        read(zip(BackupArchive.MANIFEST to "{no es json".toByteArray())) shouldBe BackupReadResult.Damaged
    }

    @Test
    fun `S013 RF-12 la copia completa necesita sus ajustes y el paquete su base de datos`() {
        read(written(files = mapOf(BackupArchive.DATABASE to file("db", "x")))) shouldBe BackupReadResult.Damaged
        val pkg = backup.copy(kind = BackupKind.PACKAGE, projectId = "p1", projectName = "P")
        read(written(pkg, files = emptyMap()), expected = BackupKind.PACKAGE) shouldBe BackupReadResult.Damaged
    }

    @Test
    fun `S013 RF-12 las entradas desconocidas se ignoran y no se extraen`() {
        val extra = entries(written()) + ("../../fuera.txt" to "x".toByteArray())

        val valid = read(zip(*extra.toTypedArray())).shouldBeInstanceOf<BackupReadResult.Valid>()

        valid.files.keys shouldBe setOf(BackupArchive.DATABASE, BackupArchive.SETTINGS)
        File(dir, "fuera.txt").exists() shouldBe false
    }

    @Test
    fun `S013 RF-13 una copia de un formato o de un esquema mas nuevos pide actualizar la app`() {
        read(written(backup.copy(schemaVersion = 7)), schemaVersion = 6) shouldBe BackupReadResult.Newer
        read(written(backup.copy(formatVersion = BackupManifest.FORMAT_VERSION + 1))) shouldBe BackupReadResult.Newer
        read(written(backup.copy(schemaVersion = 5)), schemaVersion = 6).shouldBeInstanceOf<BackupReadResult.Valid>()
    }

    @Test
    fun `S013 RF-12 una entrada mas grande que el limite se rechaza sin llenar el disco`() {
        val big = file("big", "x".repeat(2_000))
        val bytes = written(files = mapOf(BackupArchive.DATABASE to big, BackupArchive.SETTINGS to file("s", "{}")))

        BackupArchive.read(bytes.inputStream(), BackupKind.BACKUP, 6, File(dir, "limit"), maxEntryBytes = 1_000) shouldBe
            BackupReadResult.Damaged
    }

    private fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}

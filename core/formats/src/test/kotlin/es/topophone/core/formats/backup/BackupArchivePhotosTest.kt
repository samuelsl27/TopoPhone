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

/** Las fotos de los puntos en la copia y el paquete, y las externas de las copias automáticas (S017b/RF-28, RF-29, RF-30). */
class BackupArchivePhotosTest {

    @TempDir
    lateinit var dir: File

    private val backup = BackupManifest(
        kind = BackupKind.BACKUP,
        schemaVersion = 24,
        appVersion = "0.17.0",
        createdAt = Instant.parse("2026-10-31T09:00:00Z"),
        projectCount = 1,
        pointCount = 2,
    )

    private fun file(name: String, text: String) = File(dir, name).apply { writeText(text) }

    private fun baseFiles() = mapOf(
        BackupArchive.DATABASE to file("db", "SQLite format 3"),
        BackupArchive.SETTINGS to file("settings", "{}"),
    )

    private fun photoFiles() = mapOf(
        BackupArchive.photoEntry("aaa-111") to file("p1", "jpeg uno"),
        BackupArchive.photoEntry("bbb-222") to file("p2", "jpeg dos"),
    )

    private fun written(manifest: BackupManifest = backup, files: Map<String, File>): ByteArray =
        ByteArrayOutputStream().also { BackupArchive.write(manifest, files, it) }.toByteArray()

    private fun read(bytes: ByteArray, kind: BackupKind = BackupKind.BACKUP): BackupReadResult =
        BackupArchive.read(bytes.inputStream(), kind, 24, File(dir, "out-${System.nanoTime()}"))

    private fun entries(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(bytes.inputStream()).use { input ->
            while (true) add((input.nextEntry ?: break).name to input.readBytes())
        }
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { out ->
            entries.forEach { (name, data) ->
                out.putNextEntry(ZipEntry(name))
                out.write(data)
                out.closeEntry()
            }
        }
    }.toByteArray()

    @Test
    fun `S017b RF-28 las fotos van en el ZIP con su suma y vuelven iguales`() {
        val bytes = written(files = baseFiles() + photoFiles())

        val valid = read(bytes).shouldBeInstanceOf<BackupReadResult.Valid>()

        valid.files.getValue("photos/aaa-111.jpg").readText() shouldBe "jpeg uno"
        valid.files.getValue("photos/bbb-222.jpg").readText() shouldBe "jpeg dos"
        valid.manifest.files.keys shouldBe setOf("data.db", "settings.json", "photos/aaa-111.jpg", "photos/bbb-222.jpg")
        entries(bytes).first().first shouldBe BackupArchive.MANIFEST
    }

    @Test
    fun `S017b RF-29 un paquete lleva las fotos de su proyecto`() {
        val manifest = backup.copy(kind = BackupKind.PACKAGE, projectId = "p1", projectName = "Calle")
        val files = mapOf(BackupArchive.DATABASE to file("db", "x")) + photoFiles()

        val valid = read(written(manifest, files), BackupKind.PACKAGE).shouldBeInstanceOf<BackupReadResult.Valid>()

        valid.files.keys.filter { it.startsWith("photos/") }.size shouldBe 2
    }

    @Test
    fun `S017b RF-28 una entrada de foto con dos puntos o subcarpetas esta danada`() {
        val good = entries(written(files = baseFiles()))

        read(zip(good + ("photos/../fuera.jpg" to "x".toByteArray()))) shouldBe BackupReadResult.Damaged
        read(zip(good + ("photos/sub/a.jpg" to "x".toByteArray()))) shouldBe BackupReadResult.Damaged
        File(dir, "fuera.jpg").exists() shouldBe false
    }

    @Test
    fun `S017b RF-28 una entrada absoluta se ignora y no se extrae`() {
        val good = entries(written(files = baseFiles()))

        read(zip(good + ("/etc/passwd.jpg" to "x".toByteArray()))).shouldBeInstanceOf<BackupReadResult.Valid>()
    }

    @Test
    fun `S017b RF-28 una foto con la suma cambiada esta danada`() {
        val good = entries(written(files = baseFiles() + photoFiles()))
        val changed = good.map { (name, data) -> if (name == "photos/aaa-111.jpg") name to "otra".toByteArray() else name to data }

        read(zip(changed)) shouldBe BackupReadResult.Damaged
    }

    @Test
    fun `S017b RF-28 una foto de mas que el manifiesto no nombra esta danada`() {
        val good = entries(written(files = baseFiles() + photoFiles()))

        read(zip(good + ("photos/ccc-333.jpg" to "sobra".toByteArray()))) shouldBe BackupReadResult.Damaged
    }

    @Test
    fun `S017b RF-28 una foto que el manifiesto nombra y falta esta danada`() {
        val good = entries(written(files = baseFiles() + photoFiles()))

        read(zip(good.filter { it.first != "photos/bbb-222.jpg" })) shouldBe BackupReadResult.Damaged
    }

    @Test
    fun `S017b RF-28 no se escribe una foto con un nombre que no vale`() {
        val bad = baseFiles() + ("photos/../x.jpg" to file("x", "x"))

        runCatching { written(files = bad) }.isFailure shouldBe true
    }

    @Test
    fun `S017b RF-30 las fotos externas de una copia automatica van en el manifiesto y se leen`() {
        val external = mapOf("aaa-111.jpg" to "ab".repeat(32), "bbb-222.jpg" to "cd".repeat(32))
        val bytes = written(backup.copy(externalPhotos = external), baseFiles())

        val valid = read(bytes).shouldBeInstanceOf<BackupReadResult.Valid>()

        valid.manifest.externalPhotos shouldBe external
        entries(bytes).map { it.first } shouldBe listOf("manifest.json", "data.db", "settings.json")
    }

    @Test
    fun `S017b RF-30 un nombre de foto externa que no vale invalida el manifiesto`() {
        val head = """{"format":"topophone","formatVersion":1,"kind":"backup","schemaVersion":24,"appVersion":"1","""
        val manifest = (
            head + """"createdAt":"2026-10-31T09:00:00Z","projectCount":1,"pointCount":1,"files":{},""" +
                """"externalPhotos":{"../x.jpg":"00"}}"""
            ).toByteArray()

        read(zip(listOf("manifest.json" to manifest))) shouldBe BackupReadResult.Damaged
    }

    @Test
    fun `S017b RF-28 un manifiesto sin fotos externas ni de fotos se sigue leyendo`() {
        val valid = read(written(files = baseFiles())).shouldBeInstanceOf<BackupReadResult.Valid>()

        valid.manifest.externalPhotos shouldBe emptyMap()
        entries(written(files = baseFiles())).first().second.decodeToString().contains("externalPhotos") shouldBe false
    }
}

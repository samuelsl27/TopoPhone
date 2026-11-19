package es.topophone.receiver.experimental

import java.io.File

/** Lee las respuestas sintéticas de `fixtures/reach/` (S044 D14). */
object ReachFixtures {
    private val directory: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/reach") }
        .first { it.isDirectory }

    fun text(name: String): String = File(directory, name).readText().trim()
}

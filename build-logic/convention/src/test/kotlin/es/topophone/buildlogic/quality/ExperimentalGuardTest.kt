package es.topophone.buildlogic.quality

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** El flavor `standard` no lleva el módulo experimental ni nada de su flavor (S044/RF-1, ADR-18, ADR-21). */
class ExperimentalGuardTest {

    private val standardManifest = """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="es.topophone.app">
            <uses-permission android:name="android.permission.INTERNET" />
            <application android:name="es.topophone.app.TopoPhoneApplication">
                <activity android:name="es.topophone.app.MainActivity" android:exported="true" />
                <service android:name="es.topophone.app.receiver.ReceiverService" />
            </application>
        </manifest>
    """.trimIndent()

    @Test
    fun `S044 RF-1 un classpath y un manifiesto de standard no tienen infracciones`() {
        val projects = listOf(":app", ":core:model", ":data", ":receiver")

        assertEquals(emptyList<String>(), ExperimentalGuard.findViolations(projects, standardManifest))
    }

    @Test
    fun `S044 RF-1 un classpath que lleva el modulo experimental es una infraccion`() {
        val violations = ExperimentalGuard.findViolations(listOf(":app", ":receiver-experimental"), standardManifest)

        assertEquals(1, violations.size)
        assertTrue(":receiver-experimental" in violations.single())
    }

    @Test
    fun `S044 RF-1 un manifiesto con un componente del flavor es una infraccion`() {
        val manifest = standardManifest.replace(
            "</application>",
            """<activity android:name="es.topophone.app.experimental.ExperimentalActivity" /></application>""",
        )

        val violations = ExperimentalGuard.findViolations(listOf(":app"), manifest)

        assertEquals(1, violations.size)
        assertTrue("es.topophone.app.experimental.ExperimentalActivity" in violations.single())
    }

    @Test
    fun `S044 RF-1 un manifiesto con el identificador del flavor es una infraccion`() {
        val manifest = standardManifest.replace("""package="es.topophone.app"""", """package="es.topophone.app.experimental"""")

        assertEquals(1, ExperimentalGuard.findViolations(listOf(":app"), manifest).size)
    }

    @Test
    fun `S044 RF-1 un componente del modulo en el manifiesto tambien se informa`() {
        val manifest = standardManifest.replace(
            "</application>",
            """<service android:name="es.topophone.receiver.experimental.ReachService" /></application>""",
        )

        assertEquals(1, ExperimentalGuard.findViolations(listOf(":app"), manifest).size)
    }

    @Test
    fun `S044 RF-1 cada infraccion se informa una sola vez y ordenada`() {
        val violations = ExperimentalGuard.findViolations(
            listOf(":receiver-experimental", ":receiver-experimental", ":app"),
            standardManifest,
        )

        assertEquals(1, violations.size)
    }
}

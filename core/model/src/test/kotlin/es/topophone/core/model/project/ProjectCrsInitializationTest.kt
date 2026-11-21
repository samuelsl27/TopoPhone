package es.topophone.core.model.project

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import java.net.URLClassLoader
import org.junit.jupiter.api.Test

/**
 * El arranque de `ProjectCrs` y el de `SpainCrs` no se pisan (S034): en Android, abrir el formulario de proyecto daba `null` en
 * `ProjectCrs.ETRS89_UTM30` porque, con campos en el `companion` de la interfaz, el primer acceso desde la interfaz y desde el enum cruzaban sus
 * inicializaciones. En JUnit pasaba siempre porque otros tests inicializaban antes el enum; aquí se prueba en un cargador de clases limpio y
 * entrando por la interfaz, que es el orden que falló.
 */
class ProjectCrsInitializationTest {

    private fun freshLoader(): URLClassLoader {
        val urls = System.getProperty("java.class.path").split(File.pathSeparator).map { File(it).toURI().toURL() }
        return URLClassLoader(urls.toTypedArray(), ClassLoader.getPlatformClassLoader())
    }

    @Test
    fun `S034 RF-25 entrar por ProjectCrs antes que por SpainCrs da los SC de Espana y no null`() {
        freshLoader().use { loader ->
            val companion = loader.loadClass("es.topophone.core.model.project.ProjectCrs").getField("Companion").get(null)
            val getter = companion.javaClass.getMethod("getETRS89_UTM30")

            val crs = getter.invoke(companion).shouldNotBeNull()

            crs.javaClass.name shouldBe "es.topophone.core.model.project.SpainCrs"
            crs.toString() shouldBe "ETRS89_UTM30"
        }
    }

    @Test
    fun `S034 RF-25 los valores por defecto de un proyecto nuevo se construyen entrando por ProjectCrs en un cargador limpio`() {
        freshLoader().use { loader ->
            val defaults = loader.loadClass("es.topophone.core.model.project.ProjectDefaults").getDeclaredConstructor().newInstance()

            defaults.toString().contains("ETRS89_UTM30") shouldBe true
        }
    }

    @Test
    fun `S034 RF-25 entrar por SpainCrs antes que por ProjectCrs tambien funciona`() {
        freshLoader().use { loader ->
            val crs = loader.loadClass("es.topophone.core.model.project.SpainCrs").getField("ETRS89_UTM30").get(null).shouldNotBeNull()
            val companion = loader.loadClass("es.topophone.core.model.project.ProjectCrs").getField("Companion").get(null)

            companion.javaClass.getMethod("getETRS89_UTM30").invoke(companion) shouldBe crs
        }
    }
}

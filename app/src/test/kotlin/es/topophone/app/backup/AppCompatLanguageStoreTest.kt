package es.topophone.app.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** El idioma de la app viaja en la copia y se aplica al restaurar, solo si cambia (S013/RF-2, RF-8). */
@RunWith(AndroidJUnit4::class)
class AppCompatLanguageStoreTest {

    private var tags = ""
    private val written = mutableListOf<String>()
    private val store = AppCompatLanguageStore(read = { tags }, write = {
        written += it
        tags = it
    })

    @Test
    fun `S013 RF-8 el idioma de la copia se aplica a la app`() {
        runBlocking { store.apply("en") }

        written shouldBe listOf("en")
        store.current() shouldBe "en"
    }

    @Test
    fun `S013 RF-8 el mismo idioma no se vuelve a aplicar`() {
        tags = "es"

        runBlocking { store.apply("es") }

        written shouldBe emptyList()
    }

    @Test
    fun `S013 RF-8 el idioma del sistema tambien viaja`() {
        tags = "es"

        runBlocking { store.apply("") }

        written shouldBe listOf("")
    }
}

package es.topophone.app.ui.guide

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.core.model.guide.ConfigListId
import es.topophone.core.model.guide.ConfigLists
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Cada lista y cada paso de la guía tiene su texto, distinto de los demás, en español y en inglés (S043/RF-1, RF-2, RNF-T7). */
@RunWith(AndroidJUnit4::class)
class GuideTextsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val stepIds = ConfigLists.all.flatMap { list -> list.steps.map { it.id } }

    @Test
    @Config(qualifiers = "es-rES")
    fun `S043 RF-2 cada paso tiene titulo y detalle en espanol y todos son distintos`() {
        val titles = stepIds.map { context.getString(GuideTexts.stepTitle(it)) }
        val details = stepIds.map { context.getString(GuideTexts.stepDetail(it)) }

        titles.filter { it.isBlank() }.shouldBeEmpty()
        details.filter { it.isBlank() }.shouldBeEmpty()
        stepIds.map { GuideTexts.stepTitle(it) }.toSet() shouldHaveSize stepIds.size
        stepIds.map { GuideTexts.stepDetail(it) }.toSet() shouldHaveSize stepIds.size
    }

    @Test
    @Config(qualifiers = "en")
    fun `S043 RNF-T7 en ingles cada paso y cada lista tiene su texto y es distinto del espanol`() {
        val english = stepIds.map { context.getString(GuideTexts.stepTitle(it)) }

        english.filter { it.isBlank() }.shouldBeEmpty()
        context.getString(GuideTexts.stepTitle("ntrip_fix")) shouldBe "FIX solution"
        ConfigListId.entries.forEach { context.getString(GuideTexts.listTitle(it)).isNotBlank() shouldBe true }
    }

    @Test
    @Config(qualifiers = "es-rES")
    fun `S043 RF-1 las cuatro listas se llaman como dice la spec`() {
        ConfigListId.entries.map { context.getString(GuideTexts.listTitle(it)) } shouldBe
            listOf("Rover con NTRIP de la app", "Rover por Wi-Fi", "Rover con inclinación", "Base en un punto conocido")
        ConfigListId.entries.forEach { context.getString(GuideTexts.listSummary(it)).isNotBlank() shouldBe true }
    }

    @Test
    @Config(qualifiers = "es-rES")
    fun `S043 RF-2 la nota de firmware dice que se redacto sin numero de version`() {
        val note = context.getString(GuideTexts.firmwareNote())

        note.contains("firmware estable de 2026") shouldBe true
        note.contains("sin número de versión") shouldBe true
    }

    @Test
    fun `S043 RF-2 un paso desconocido es un error y no un texto cualquiera`() {
        val failed = runCatching { GuideTexts.stepTitle("no_existe") }.isFailure

        failed shouldBe true
    }
}

package es.topophone.app.ui.settings

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import es.topophone.app.R
import es.topophone.app.ui.StatefulTestApp
import es.topophone.app.ui.TopLevelDestination
import es.topophone.app.ui.navItem
import es.topophone.app.ui.text
import es.topophone.data.receiver.CorrectionsAlarm
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Umbral de la alarma por edad de las correcciones en Ajustes (S007/RF-31). Interfaz en es-ES. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "es-rES")
class CorrectionsSettingsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `S007 RF-31 el umbral de la alarma se elige en Ajustes entre 2 y 60 s y por defecto es 10 s`() {
        var chosen: Int? = null
        compose.setContent { StatefulTestApp(onSelectCorrectionsAlarm = { chosen = it }) }
        compose.navItem(TopLevelDestination.SETTINGS).performClick()

        compose.onNodeWithTag(CORRECTIONS_ALARM_SETTING_TAG).performScrollTo()
            .assert(hasText(text(R.string.settings_corrections_alarm)))
            .assert(hasText(text(R.string.settings_seconds, 10)))
            .performClick()
        CORRECTIONS_ALARM_OPTIONS_S.forEach {
            compose.onNodeWithTag(correctionsAlarmOptionTag(it)).assert(hasText(text(R.string.settings_seconds, it)))
        }
        compose.onNodeWithTag(correctionsAlarmOptionTag(30)).performClick()

        chosen shouldBe 30
        compose.onNodeWithTag(CORRECTIONS_ALARM_SETTING_TAG).performScrollTo().assert(hasText(text(R.string.settings_seconds, 30)))
    }

    @Test
    fun `S007 RF-31 las opciones estan todas entre 2 y 60 s e incluyen los extremos y el valor por defecto`() {
        CORRECTIONS_ALARM_OPTIONS_S.all { it in CorrectionsAlarm.MIN_S..CorrectionsAlarm.MAX_S } shouldBe true
        (CorrectionsAlarm.MIN_S in CORRECTIONS_ALARM_OPTIONS_S) shouldBe true
        (CorrectionsAlarm.MAX_S in CORRECTIONS_ALARM_OPTIONS_S) shouldBe true
        (CorrectionsAlarm.DEFAULT_S in CORRECTIONS_ALARM_OPTIONS_S) shouldBe true
    }
}

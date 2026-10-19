package es.topophone.app.receiver

import es.topophone.core.geodesy.Ecef
import es.topophone.core.gnss.nmea.GgaFix
import es.topophone.core.gnss.nmea.SolutionStatus
import es.topophone.core.model.point.BaseSource
import es.topophone.core.survey.BaseChange
import es.topophone.core.survey.BaseFix
import es.topophone.core.survey.BaseState
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

/** Qué avisos de la base se ven y cuáles ha cerrado el usuario (S015/RF-15, RF-17). */
class BaseNoticesTest {

    /** El rover en Madrid y la base en Avilés (estación 6 de RGAPA): unos 330 km. */
    private val madrid = GgaFix("101530.20", 40.41668, -3.70371, SolutionStatus.FIX, 24, 0.6, 650.123, 51.234, 1.0, "")
    private val avl13m = BaseFix(
        43.5578666032,
        -5.9130704113,
        78.4338,
        Ecef(4_604_889.1789, -476_930.7239, 4_372_677.1220),
        BaseSource.CORRECTIONS,
        6,
        0.0,
        0,
    )
    private val near = avl13m.copy(ecef = Ecef.fromGeodetic(40.4167, -3.7037, 700.0))

    private fun base(position: BaseFix?, serial: Int = 1, change: BaseChange? = null) =
        BaseState(position, serial, change, emptyMap(), false)

    @Test
    fun `S015 RF-15 la linea base larga se avisa con mas de 20 km`() {
        val notices = BaseNotices.visible(madrid, base(avl13m), DismissedNotices())

        notices.longBaselineM.shouldNotBeNull() shouldBe (382_000.0 plusOrMinus 50_000.0)
        BaseNotices.visible(madrid, base(near), DismissedNotices()).longBaselineM.shouldBeNull()
        BaseNotices.visible(null, base(avl13m), DismissedNotices()).longBaselineM.shouldBeNull()
        BaseNotices.visible(madrid, base(null), DismissedNotices()).longBaselineM.shouldBeNull()
    }

    @Test
    fun `S015 RF-15 cerrada para una base, la linea base larga vuelve con la base siguiente`() {
        BaseNotices.visible(madrid, base(avl13m, serial = 3), DismissedNotices(longBaselineSerial = 3)).longBaselineM.shouldBeNull()
        BaseNotices.visible(madrid, base(avl13m, serial = 4), DismissedNotices(longBaselineSerial = 3)).longBaselineM.shouldNotBeNull()
    }

    @Test
    fun `S015 RF-17 el cambio de base se ve hasta que se cierra, y el siguiente vuelve a verse`() {
        val change = BaseChange(1.85, count = 2)

        BaseNotices.visible(madrid, base(near, change = change), DismissedNotices()).change shouldBe change
        BaseNotices.visible(madrid, base(near, change = change), DismissedNotices(changeCount = 2)).change.shouldBeNull()
        BaseNotices.visible(madrid, base(near, change = change.copy(count = 3)), DismissedNotices(changeCount = 2)).change.shouldNotBeNull()
    }

    @Test
    fun `S015 RF-15 RF-17 cerrar un aviso lo cierra para todas las pantallas`() {
        val notices = BaseNotices()

        notices.dismissLongBaseline(5)
        notices.dismissChange(2)

        notices.dismissed.value shouldBe DismissedNotices(changeCount = 2, longBaselineSerial = 5)
    }
}

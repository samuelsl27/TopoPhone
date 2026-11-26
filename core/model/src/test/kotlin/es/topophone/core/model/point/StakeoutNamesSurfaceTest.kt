package es.topophone.core.model.point

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** El nombre de los puntos replanteados contra la superficie: SUP1, SUP2… con el primer número libre (S062/RF-16). */
class StakeoutNamesSurfaceTest {

    @Test
    fun `S062 RF-16 el primero es SUP1 y luego el primer numero libre`() {
        StakeoutNames.nextSurface(emptySet()) shouldBe "SUP1"
        StakeoutNames.nextSurface(setOf("SUP1")) shouldBe "SUP2"
        StakeoutNames.nextSurface(setOf("SUP1", "SUP3")) shouldBe "SUP2"
    }

    @Test
    fun `S062 RF-16 los demas nombres del proyecto no cuentan`() {
        StakeoutNames.nextSurface(setOf("1", "SUP", "sup1", "SUP1_stkd")) shouldBe "SUP1"
    }

    @Test
    fun `S062 RF-21 un SUP no tiene numero de replanteo`() {
        StakeoutNames.number("SUP2").shouldBeNull()
    }
}

package es.topophone.core.model.receiver

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test

/** El formulario del receptor Wi-Fi: valores iniciales y lo que impide guardar (S021/RF-2, RF-3). */
class WifiReceiverValidatorTest {

    private fun result(name: String = "RS3", host: String = "192.168.42.1", position: String = "9001", corrections: String = "") =
        WifiReceiverValidator.validate(WifiReceiverDraft(name, host, position, corrections))

    private fun valid(name: String = "RS3", host: String = "192.168.42.1", position: String = "9001", corrections: String = "") =
        result(name, host, position, corrections).shouldBeInstanceOf<WifiReceiverValidation.Valid>()

    private fun problems(name: String = "RS3", host: String = "192.168.42.1", position: String = "9001", corrections: String = "") =
        result(name, host, position, corrections).shouldBeInstanceOf<WifiReceiverValidation.Invalid>().problems

    @Test
    fun `S021 RF-2 los valores iniciales son 192-168-42-1 y 9001 y las correcciones van vacias`() {
        val initial = WifiReceiverDraft.initial()

        initial.host shouldBe "192.168.42.1"
        initial.positionPort shouldBe "9001"
        initial.correctionsPort shouldBe ""
        initial.name shouldBe ""
        WifiReceiverDefaults.HOST shouldBe "192.168.42.1"
        WifiReceiverDefaults.POSITION_PORT shouldBe 9001
    }

    @Test
    fun `S021 RF-2 un formulario correcto da el receptor con sus puertos y sin puerto de correcciones si va vacio`() {
        val input = valid(name = "  RS3 hotspot ", host = " 192.168.42.1 ", position = " 9001 ").input

        input.name shouldBe "RS3 hotspot"
        input.host shouldBe "192.168.42.1"
        input.positionPort shouldBe 9001
        input.correctionsPort shouldBe null
        valid(corrections = "9002").input.correctionsPort shouldBe 9002
        valid(corrections = "9001").input.correctionsPort shouldBe 9001 // el mismo socket (S021/RF-11)
    }

    @Test
    fun `S021 RF-2 sin nombre se usa la direccion con el puerto`() {
        valid(name = "  ", host = "192.168.0.20", position = "3000").input.name shouldBe "192.168.0.20:3000"
    }

    @Test
    fun `S021 RF-3 las direcciones validas son IPv4, nombres de equipo e IPv6 entre corchetes`() {
        val hosts =
            listOf(
                "192.168.42.1",
                "10.0.0.255",
                "0.0.0.0",
                "reach.local",
                "rs3-campo",
                "RS3.casa.example.org",
                "[fe80::1]",
                "[2001:db8::7334]",
            )

        for (host in hosts) valid(host = host).input.host shouldBe host
    }

    @Test
    fun `S021 RF-3 una direccion vacia no se guarda y se dice`() {
        problems(host = "") shouldBe setOf(WifiReceiverProblem.HOST_EMPTY)
        problems(host = "   ") shouldBe setOf(WifiReceiverProblem.HOST_EMPTY)
    }

    @Test
    fun `S021 RF-3 una direccion invalida no se guarda`() {
        val hosts = listOf(
            "192.168.42", "192.168.42.256", "999.1.1.1", "1.2.3.4.5", "a b", "http://192.168.42.1", "192.168.42.1:9001", "192.168.42.1/",
            "-malo.local", "malo-.local", "malo..local", ".local", "[fe80::1", "[]", "[zz::1]", "[::1]x", "reach_local",
        )

        for (host in hosts) {
            problems(host = host) shouldBe setOf(WifiReceiverProblem.HOST_INVALID)
        }
    }

    @Test
    fun `S021 RF-3 un puerto de posicion que no es un numero entre 1 y 65535 no se guarda`() {
        for (port in listOf("", "0", "65536", "-1", "abc", "90 01", "9001.5", "1e3", "999999999999")) {
            problems(position = port) shouldBe setOf(WifiReceiverProblem.POSITION_PORT_INVALID)
        }
        valid(position = "1").input.positionPort shouldBe 1
        valid(position = "65535").input.positionPort shouldBe 65_535
    }

    @Test
    fun `S021 RF-3 un puerto de correcciones escrito y no valido tambien impide guardar`() {
        for (port in listOf("0", "65536", "x", "-5")) {
            problems(corrections = port) shouldBe setOf(WifiReceiverProblem.CORRECTIONS_PORT_INVALID)
        }
    }

    @Test
    fun `S021 RF-3 se dicen todos los problemas a la vez`() {
        problems(host = "", position = "0", corrections = "x") shouldBe
            setOf(WifiReceiverProblem.HOST_EMPTY, WifiReceiverProblem.POSITION_PORT_INVALID, WifiReceiverProblem.CORRECTIONS_PORT_INVALID)
    }

    @Test
    fun `S021 RF-3 los puertos menores de 3000 se avisan como reservados pero se aceptan`() {
        val reserved = valid(position = "2999", corrections = "80")

        reserved.warnings shouldBe setOf(WifiReceiverWarning.RESERVED_POSITION_PORT, WifiReceiverWarning.RESERVED_CORRECTIONS_PORT)
        reserved.input.positionPort shouldBe 2_999
        valid(position = "3000").warnings.shouldBeEmpty()
        valid(position = "9001", corrections = "9002").warnings.shouldBeEmpty()
    }
}

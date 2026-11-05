package es.topophone.core.survey

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.File
import org.junit.jupiter.api.Test

/** La cota de diseño de una línea con alturas en solo algunos vértices, frente a `fixtures/replanteo/cota-parcial/` (S027/RF-8, RF-9). */
class StakeLinePartialHeightTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }

    private fun rows(name: String): List<Map<String, String>> {
        val lines = File(root, "fixtures/replanteo/cota-parcial/$name").readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        val header = lines.first().split(",")
        return lines.drop(1).map { header.zip(it.split(",", limit = header.size)).toMap() }
    }

    private fun line(case: String): StakeLine {
        val vertices = rows("casos.csv").filter { it.getValue("caso") == case }
        return StakeLine.create(
            DoubleArray(vertices.size) { vertices[it].getValue("este").toDouble() },
            DoubleArray(vertices.size) { vertices[it].getValue("norte").toDouble() },
            DoubleArray(vertices.size) { vertices[it].getValue("cota").toDoubleOrNull() ?: Double.NaN },
            closed = vertices.first().getValue("cerrada") == "1",
        )!!
    }

    private val cases = rows("casos.csv").map { it.getValue("caso") }.distinct()

    @Test
    fun `S027 RF-8 la cota de cada PK coincide con la referencia en todos los casos`() {
        for (case in cases) {
            val line = line(case)
            for (e in rows("esperado.csv").filter { it.getValue("caso") == case }) {
                val expected = e.getValue("cota").toDoubleOrNull()
                val actual = line.heightAt(e.getValue("pk").toDouble())
                if (expected == null) {
                    actual.shouldBeNull()
                } else {
                    actual.shouldNotBeNull() shouldBe (expected plusOrMinus 1e-9)
                }
            }
        }
    }

    @Test
    fun `S027 RF-9 antes del primer vertice con altura y despues del ultimo no hay cota`() {
        val line = line("abierta_extremos")

        line.heightAt(0.0).shouldBeNull()
        line.heightAt(49.9).shouldBeNull()
        line.heightAt(150.1).shouldBeNull()
        line.heightAt(200.0).shouldBeNull()
    }

    @Test
    fun `S027 RF-8 un vertice trae su altura y los que no la tienen no`() {
        val line = line("abierta_extremos")

        line.height(0).shouldBeNull()
        line.height(1) shouldBe 10.0
        line.height(2).shouldBeNull()
        line.hasHeight shouldBe true
        line.hasCompleteHeight shouldBe false
    }

    @Test
    fun `S027 RF-9 una linea sin ninguna altura no tiene cota`() {
        val line = line("sin_alturas")

        line.hasHeight shouldBe false
        line.heightAt(25.0).shouldBeNull()
    }

    @Test
    fun `S027 RF-8 con todas las alturas es la interpolacion de S041, tambien fuera de la linea`() {
        val line = line("abierta_completa")

        line.hasCompleteHeight shouldBe true
        line.heightAt(25.0).shouldNotBeNull() shouldBe (6.0 plusOrMinus 1e-12)
        line.heightAt(-10.0).shouldNotBeNull() shouldBe (4.6 plusOrMinus 1e-12) // extrapola con el primer tramo, como S041
    }

    @Test
    fun `S027 RF-8 en un poligono el cierre interpola y un PK de mas de una vuelta da la vuelta`() {
        val line = line("poligono_parcial")

        line.heightAt(100.0).shouldNotBeNull() shouldBe (20.0 - 10.0 * 30.0 / 70.0 plusOrMinus 1e-9)
        line.heightAt(240.0).shouldNotBeNull() shouldBe (line.heightAt(100.0).shouldNotBeNull() plusOrMinus 1e-9)
    }

    @Test
    fun `S027 RF-8 si un duplicado seguido tiene la altura que le falta al primero, la conserva`() {
        val line = StakeLine.create(
            doubleArrayOf(0.0, 0.0, 30.0, 60.0),
            doubleArrayOf(0.0, 0.0, 40.0, 80.0),
            doubleArrayOf(Double.NaN, 5.0, Double.NaN, 9.0),
        )!!

        line.vertexCount shouldBe 3
        line.height(0) shouldBe 5.0
    }

    @Test
    fun `S027 RF-5 createWithSource dice de que vertice de la entrada sale cada vertice que queda`() {
        val built = StakeLine.createWithSource(
            doubleArrayOf(0.0, 0.0, 30.0, 60.0, 0.0),
            doubleArrayOf(0.0, 0.0, 40.0, 80.0, 0.0),
            null,
        )!!

        built.line.closed shouldBe true
        built.line.vertexCount shouldBe 3
        built.sourceIndex.toList() shouldBe listOf(0, 2, 3)
    }

    @Test
    fun `S027 RF-3 createWithSource con menos de dos vertices distintos no crea la linea`() {
        StakeLine.createWithSource(doubleArrayOf(1.0, 1.0), doubleArrayOf(2.0, 2.0), null).shouldBeNull()
    }
}

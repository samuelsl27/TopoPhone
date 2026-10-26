package es.topophone.core.formats.dxf

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.jupiter.api.Test

/** Arcos, círculos, bulges y splines como polilíneas a menos de 1 cm de la curva (S040/RF-15). */
class CadCurvesTest {
    private val tol = CadCurves.TOLERANCE_M

    private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun distToPolyline(px: Double, py: Double, xs: DoubleArray, ys: DoubleArray): Double =
        (0 until xs.size - 1).minOf { distToSegment(px, py, xs[it], ys[it], xs[it + 1], ys[it + 1]) }

    /** La mayor distancia de los puntos de las cuerdas de [c] al círculo de centro (cx, cy) y radio r. */
    private fun maxSagitta(c: Curve2, cx: Double, cy: Double, r: Double): Double = (0 until c.x.size - 1).maxOf { i ->
        val mx = (c.x[i] + c.x[i + 1]) / 2
        val my = (c.y[i] + c.y[i + 1]) / 2
        r - hypot(mx - cx, my - cy)
    }

    private fun entities(name: String): List<DxfEntity> {
        val tags = DxfTagReader(ByteArrayInputStream(DxfFixtures.bytes(name)))
        DxfFixtures.skipToEntities(tags)
        val stream = DxfEntityStream(tags)
        return generateSequence { stream.next() }.filterIsInstance<DxfItem.Entity>().map { it.entity }.toList()
    }

    @Test
    fun `S040 RF-15 un circulo grande tiene los segmentos justos para quedar a menos de 1 cm`() {
        val circle = CadCurves.circle(100.0, 200.0, 25.0, tol)
        val expected = ceil(PI / acos(1 - tol / 25.0)).toInt()

        (circle.x.size - 1) shouldBe expected
        circle.x.indices.forEach { hypot(circle.x[it] - 100.0, circle.y[it] - 200.0) shouldBe (25.0 plusOrMinus 1e-9) }
        (maxSagitta(circle, 100.0, 200.0, 25.0) <= tol + 1e-12) shouldBe true
        circle.x.first() shouldBe circle.x.last() // el anillo se cierra
        circle.y.first() shouldBe circle.y.last()
    }

    @Test
    fun `S040 RF-15 un circulo de radio menor que la tolerancia es un punto en su centro`() {
        val tiny = CadCurves.circle(10.0, 20.0, 0.005, tol)

        tiny.x.toList() shouldBe listOf(10.0)
        tiny.y.toList() shouldBe listOf(20.0)
    }

    @Test
    fun `S040 RF-15 un circulo pequeno tiene al menos cuatro segmentos`() {
        (CadCurves.circle(0.0, 0.0, 0.02, tol).x.size - 1 >= 4) shouldBe true
    }

    @Test
    fun `S040 RF-15 el arco del fichero empieza y acaba donde dicen sus angulos y esta a menos de 1 cm`() {
        val arc = entities("r2000.dxf").filterIsInstance<DxfArc>().single()
        val curve = CadCurves.arc(arc.centerX, arc.centerY, arc.radius, arc.startDegrees, arc.endDegrees, tol)

        val start = Math.toRadians(20.0)
        val end = Math.toRadians(140.0)
        curve.x.first() shouldBe (arc.centerX + 120.0 * kotlin.math.cos(start) plusOrMinus 1e-9)
        curve.y.first() shouldBe (arc.centerY + 120.0 * kotlin.math.sin(start) plusOrMinus 1e-9)
        curve.x.last() shouldBe (arc.centerX + 120.0 * kotlin.math.cos(end) plusOrMinus 1e-9)
        curve.y.last() shouldBe (arc.centerY + 120.0 * kotlin.math.sin(end) plusOrMinus 1e-9)
        curve.x.indices.forEach { hypot(curve.x[it] - arc.centerX, curve.y[it] - arc.centerY) shouldBe (120.0 plusOrMinus 1e-9) }
        (maxSagitta(curve, arc.centerX, arc.centerY, 120.0) <= tol + 1e-12) shouldBe true
        val angles = curve.x.indices.map { atan2(curve.y[it] - arc.centerY, curve.x[it] - arc.centerX) }
        angles.zipWithNext().all { (a, b) -> b > a } shouldBe true // sentido antihorario
    }

    @Test
    fun `S040 RF-15 un arco que cruza el angulo 0 barre solo la diferencia`() {
        val curve = CadCurves.arc(0.0, 0.0, 50.0, 350.0, 10.0, tol)

        curve.x.first() shouldBe (50.0 * kotlin.math.cos(Math.toRadians(350.0)) plusOrMinus 1e-9)
        curve.x.last() shouldBe (50.0 * kotlin.math.cos(Math.toRadians(10.0)) plusOrMinus 1e-9)
        (curve.x.size < 30) shouldBe true // 20° de arco, no 340°
    }

    @Test
    fun `S040 RF-15 un arco de angulos iguales es una circunferencia entera`() {
        val curve = CadCurves.arc(0.0, 0.0, 10.0, 90.0, 90.0, tol)

        curve.x.first() shouldBe (curve.x.last() plusOrMinus 1e-9)
        (curve.x.size > 20) shouldBe true
    }

    @Test
    fun `S040 RF-15 un radio muy grande no da una explosion de vertices`() {
        val curve = CadCurves.arc(0.0, 0.0, 1.0e6, 0.0, 30.0, tol)

        (curve.x.size < 20_000) shouldBe true
        (maxSagitta(curve, 0.0, 0.0, 1.0e6) <= tol * 1.001) shouldBe true
    }

    @Test
    fun `S040 RF-15 la tolerancia se adapta a la unidad del dibujo`() {
        val feet = CadCurves.circle(0.0, 0.0, 100.0, tol / 0.3048)

        (maxSagitta(feet, 0.0, 0.0, 100.0) <= tol / 0.3048 + 1e-12) shouldBe true
        (feet.x.size - 1) shouldBe ceil(PI / acos(1 - (tol / 0.3048) / 100.0)).toInt()
    }

    @Test
    fun `S040 RF-15 un bulge de 0,5 sobre una cuerda de 30 m es el arco de centro 45 y 11,25`() {
        val curve = CadCurves.bulge(30.0, 0.0, 60.0, 0.0, 0.5, tol)

        // theta = 4 atan(0,5); r = 15 / sin(theta / 2) = 18,75; el centro queda a la izquierda de la cuerda: (45, 11,25).
        (curve.x.size > 1) shouldBe true
        curve.x.indices.forEach { hypot(curve.x[it] - 45.0, curve.y[it] - 11.25) shouldBe (18.75 plusOrMinus 1e-9) }
        curve.y.all { it < 0.0 } shouldBe true // el arco se curva hacia abajo, por debajo de la cuerda
        curve.x.all { it > 30.0 && it < 60.0 } shouldBe true // y solo trae los puntos de en medio
    }

    @Test
    fun `S040 RF-15 un bulge negativo se curva al otro lado y uno de 1 es una semicircunferencia`() {
        val negative = CadCurves.bulge(30.0, 0.0, 60.0, 0.0, -0.5, tol)
        negative.indices().forEach { hypot(negative.x[it] - 45.0, negative.y[it] + 11.25) shouldBe (18.75 plusOrMinus 1e-9) }
        negative.y.all { it > 0.0 } shouldBe true

        val half = CadCurves.bulge(0.0, 0.0, 20.0, 0.0, 1.0, tol)
        half.indices().forEach { hypot(half.x[it] - 10.0, half.y[it]) shouldBe (10.0 plusOrMinus 1e-9) }
    }

    @Test
    fun `S040 RF-15 un bulge de 0 no anade vertices y un tramo sin longitud tampoco`() {
        CadCurves.bulge(0.0, 0.0, 10.0, 0.0, 0.0, tol).x.size shouldBe 0
        CadCurves.bulge(5.0, 5.0, 5.0, 5.0, 0.5, tol).x.size shouldBe 0
    }

    @Test
    fun `S040 RF-15 las splines del fichero se leen con su grado nudos puntos de control y pesos`() {
        val splines = entities("r2000.dxf").filterIsInstance<DxfSpline>()
        val want = DxfFixtures.expected("esperado.csv").filter { it["archivo"] == "r2000.dxf" && it["tipo_elemento"] == "SPLINE" }

        splines.size shouldBe 2
        splines.forEach {
            it.degree shouldBe 3
            it.knots shouldBe listOf(0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 3.0, 3.0, 3.0, 3.0)
            it.controlPoints.size shouldBe 6
        }
        splines[0].weights shouldBe null
        splines[1].weights shouldBe listOf(1.0, 2.0, 1.0, 0.5, 1.0, 1.0)
        splines[0].controlPoints.map { listOf(it.x, it.y, it.z) } shouldBe
            want[0].getValue("vertices").split(";").map { v -> v.split(" ").take(3).map { it.toDouble() } }
    }

    @Test
    fun `S040 RF-15 la spline queda a menos de 1 cm de la curva de de Boor calculada en tools, y la curva de ella`() {
        val splines = entities("r2000.dxf").filterIsInstance<DxfSpline>()
        val want = DxfFixtures.expected("esperado.csv").filter { it["archivo"] == "r2000.dxf" && it["tipo_elemento"] == "SPLINE" }

        splines.zip(want).forEach { (spline, row) ->
            val curve = CadCurves.spline(spline, tol)
            val dense = row.getValue("densa").split(";").map { p -> p.split(" ").map { it.toDouble() } }
            val dx = dense.map { it[0] }.toDoubleArray()
            val dy = dense.map { it[1] }.toDoubleArray()

            curve.x.first() shouldBe (spline.controlPoints.first().x plusOrMinus 1e-9)
            curve.x.last() shouldBe (spline.controlPoints.last().x plusOrMinus 1e-6)
            curve.x.indices.forEach { (distToPolyline(curve.x[it], curve.y[it], dx, dy) <= tol / 2) shouldBe true }
            dx.indices.forEach { (distToPolyline(dx[it], dy[it], curve.x, curve.y) <= tol) shouldBe true }
            (curve.x.size < 2000) shouldBe true
        }
    }

    @Test
    fun `S040 RF-15 una spline cubica de Bezier da la curva de Bernstein calculada a mano`() {
        val ctrl = listOf(DxfVertex(0.0, 0.0, 0.0), DxfVertex(10.0, 30.0, 0.0), DxfVertex(30.0, 30.0, 0.0), DxfVertex(40.0, 0.0, 0.0))
        val spline = DxfSpline(DxfStyle("0", null, null), 3, listOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0), ctrl, null, emptyList(), false)

        val curve = CadCurves.spline(spline, tol)

        val dense = (0..1000).map { i ->
            val t = i / 1000.0
            val b = doubleArrayOf((1 - t) * (1 - t) * (1 - t), 3 * t * (1 - t) * (1 - t), 3 * t * t * (1 - t), t * t * t)
            b.indices.sumOf { b[it] * ctrl[it].x } to b.indices.sumOf { b[it] * ctrl[it].y }
        }
        val dx = dense.map { it.first }.toDoubleArray()
        val dy = dense.map { it.second }.toDoubleArray()
        curve.x.indices.forEach { (distToPolyline(curve.x[it], curve.y[it], dx, dy) <= tol / 2) shouldBe true }
        dx.indices.forEach { (distToPolyline(dx[it], dy[it], curve.x, curve.y) <= tol) shouldBe true }
        curve.z!!.all { it == 0.0 } shouldBe true
    }

    @Test
    fun `S040 RF-15 una spline racional de un cuarto de circulo queda a menos de 1 cm de la circunferencia`() {
        val w = sqrt(2.0) / 2
        val ctrl = listOf(DxfVertex(100.0, 0.0, null), DxfVertex(100.0, 100.0, null), DxfVertex(0.0, 100.0, null))
        val spline =
            DxfSpline(DxfStyle("0", null, null), 2, listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), ctrl, listOf(1.0, w, 1.0), emptyList(), false)

        val curve = CadCurves.spline(spline, tol)

        curve.x.indices.forEach { hypot(curve.x[it], curve.y[it]) shouldBe (100.0 plusOrMinus 1e-9) }
        (maxSagitta(Curve2(curve.x, curve.y), 0.0, 0.0, 100.0) <= tol + 1e-9) shouldBe true
        curve.z shouldBe null // sin Z en los puntos de control, sin Z en la curva
    }

    @Test
    fun `S040 RF-15 una spline con solo puntos de ajuste es la polilinea por ellos`() {
        val fit = listOf(DxfVertex(0.0, 0.0, null), DxfVertex(5.0, 5.0, null), DxfVertex(10.0, 0.0, null))
        val spline = DxfSpline(DxfStyle("0", null, null), 3, emptyList(), emptyList(), null, fit, false)

        val curve = CadCurves.spline(spline, tol)

        curve.x.toList() shouldBe listOf(0.0, 5.0, 10.0)
        curve.y.toList() shouldBe listOf(0.0, 5.0, 0.0)
    }

    @Test
    fun `S040 RF-13 una spline con nudos que no cuadran es un elemento roto`() {
        val bytes = listOf(
            "0", "SECTION", "2", "ENTITIES", "0", "SPLINE", "8", "C", "71", "3", "72", "3", "73", "4", "40", "0", "40", "0", "40", "1",
            "10", "0", "20", "0", "30", "0", "10", "1", "20", "1", "30", "0",
            "10", "2", "20", "0", "30", "0", "10", "3", "20", "1", "30", "0",
            "0", "ENDSEC", "0", "EOF",
        ).joinToString("\n", postfix = "\n").toByteArray()
        val tags = DxfTagReader(ByteArrayInputStream(bytes))
        DxfFixtures.skipToEntities(tags)

        DxfEntityStream(tags).next() shouldBe DxfItem.Broken("SPLINE")
    }

    private fun Curve2.indices() = x.indices
}

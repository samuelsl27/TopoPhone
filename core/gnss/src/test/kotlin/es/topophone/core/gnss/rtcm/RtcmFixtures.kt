package es.topophone.core.gnss.rtcm

import java.io.File

/** El flujo RTCM real de `fixtures/rtcm/rgapa-AVL13M.rtcm3` (RGAPA, Avilés: 1006 y MSM5) y los datos de sus tramas. */
object RtcmFixtures {

    val stream: ByteArray by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").exists() && File(it, "fixtures").isDirectory }
        File(root, "fixtures/rtcm/rgapa-AVL13M.rtcm3").readBytes()
    }

    /** Los datos de cada trama buena del flujo, en orden, con su tipo. */
    val payloads: List<Pair<Int, ByteArray>> by lazy {
        val list = ArrayList<Pair<Int, ByteArray>>()
        RtcmFramer { type, payload -> list += type to payload }.feed(stream)
        list
    }

    fun payloadsOf(type: Int): List<ByteArray> = payloads.filter { it.first == type }.map { it.second }
}

package es.topophone.core.gnss.nmea

import es.topophone.core.gnss.signal.Band
import es.topophone.core.gnss.signal.SatelliteId
import es.topophone.core.gnss.signal.SatelliteNumber
import es.topophone.core.gnss.signal.SignalKey

/** Dilución de la precisión de la GSA (S015/RF-2). */
data class Dop(val pdop: Double?, val hdop: Double?, val vdop: Double?)

/** Satélites usados y en vista de una constelación (S015/RF-3). */
data class SatelliteCount(val used: Int, val inView: Int)

/** Un satélite del skyplot: ángulos en radianes y si se usa en la solución (S015/RF-4, RF-6). */
data class SkySatellite(val id: SatelliteId, val elevationRad: Double, val azimuthRad: Double, val used: Boolean)

/**
 * Lo último conocido de las GSA y las GSV. El RS3 las manda a 1 Hz y la GGA a 5 Hz, así que cuatro de cada cinco épocas
 * vienen sin ellas y la vista se conserva (plan de S015, A8).
 *
 * [used] lleva los números propios de cada constelación (`SatelliteNumber`); [inView], los grupos GSV tal como llegan.
 */
data class SkyView(
    val dop: Dop?,
    val used: Map<Constellation, Set<Int>>,
    val usedCounts: Map<Constellation, Int>,
    val inView: Map<GsvKey, SatellitesInView>,
    val updatedAtMs: Long,
) {
    /** Por constelación: los usados de su GSA y, en vista, el mayor total de sus señales, como S003/RF-15. */
    val counts: Map<Constellation, SatelliteCount> by lazy {
        val inViewCounts = inView.entries.groupBy({ it.key.constellation }, { it.value.count }).mapValues { it.value.max() }
        (usedCounts.keys + inViewCounts.keys).associateWith { SatelliteCount(usedCounts[it] ?: 0, inViewCounts[it] ?: 0) }
    }

    /** Un punto por satélite con elevación y azimut, aunque esté en varias señales (S015/RF-4, RF-6). */
    val satellites: List<SkySatellite> by lazy {
        val seen = LinkedHashMap<SatelliteId, SkySatellite>()
        for ((key, group) in inView) {
            for (view in group.satellites) {
                val number = SatelliteNumber.native(key.constellation, view.prn) ?: continue
                val elevation = view.elevationRad ?: continue
                val azimuth = view.azimuthRad ?: continue
                val id = SatelliteId(key.constellation, number)
                if (id !in seen) seen[id] = SkySatellite(id, elevation, azimuth, number in used[key.constellation].orEmpty())
            }
        }
        seen.values.toList()
    }

    /**
     * SNR del rover por satélite y banda (S015/RF-7): sin banda si la GSV no indica la señal, y `null` si el satélite está
     * en vista pero el receptor no da su SNR. Dos señales de la misma banda se quedan con la mayor.
     */
    val roverSnr: Map<SignalKey, Int?> by lazy {
        val snr = LinkedHashMap<SignalKey, Int?>()
        for ((key, group) in inView) {
            val band = Band.fromNmea(key.constellation, key.signalId)
            for (view in group.satellites) {
                val number = SatelliteNumber.native(key.constellation, view.prn) ?: continue
                val signal = SignalKey(SatelliteId(key.constellation, number), band)
                val previous = snr[signal]
                snr[signal] = if (previous == null) view.snrDbHz else maxOf(previous, view.snrDbHz ?: previous)
            }
        }
        snr
    }

    companion object {
        /** La vista tras [epoch]: lo que traiga de GSA o de GSV sustituye a lo de [previous]; sin ninguna, [previous]. */
        fun next(previous: SkyView?, epoch: Epoch): SkyView? {
            if (epoch.gsa.isEmpty() && epoch.gsv.isEmpty()) return previous
            val gsa = epoch.gsa.takeIf { it.isNotEmpty() }
            return SkyView(
                dop = if (gsa !=
                    null
                ) {
                    gsa.values.firstOrNull { it.pdop != null || it.hdop != null || it.vdop != null }?.dop()
                } else {
                    previous?.dop
                },
                used = gsa?.mapValues { (constellation, data) ->
                    data.satellitesUsed.mapNotNull { SatelliteNumber.native(constellation, it) }.toSet()
                } ?: previous?.used.orEmpty(),
                usedCounts = gsa?.mapValues { it.value.satellitesUsed.size } ?: previous?.usedCounts.orEmpty(),
                inView = epoch.gsv.takeIf { it.isNotEmpty() } ?: previous?.inView.orEmpty(),
                updatedAtMs = epoch.completedAtMs,
            )
        }

        private fun GsaData.dop() = Dop(pdop, hdop, vdop)
    }
}

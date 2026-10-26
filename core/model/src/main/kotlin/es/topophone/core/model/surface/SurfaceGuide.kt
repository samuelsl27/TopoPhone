package es.topophone.core.model.surface

/** De dónde sale la cota de la superficie en una posición (S042/RF-22, RF-23); no bloquea, así que puede responder [SurfaceHeight.Loading]. */
fun interface SurfaceHeightSource {
    fun heightAt(easting: Double, northing: Double): SurfaceHeight
}

/**
 * La superficie activa de un proyecto lista para el replanteo y el recolector (S042/RF-24 a RF-26, RF-33): la superficie, su fuente de
 * cotas y el desplazamiento vertical del proyecto en metros, positivo hacia arriba (RF-19).
 */
class SurfaceGuide(val surface: Surface, private val source: SurfaceHeightSource, val verticalOffsetM: Double) {
    /** La cota de la superficie en la posición, sin desplazamiento. */
    fun heightAt(easting: Double, northing: Double): SurfaceHeight = source.heightAt(easting, northing)

    /** La cota de la superficie con el desplazamiento vertical (RF-24); fuera y cargando no tienen cota. */
    fun designAt(easting: Double, northing: Double): SurfaceHeight = heightAt(easting, northing).withOffset(verticalOffsetM)

    /** La misma superficie con otro desplazamiento vertical. */
    fun withOffset(offsetM: Double) = SurfaceGuide(surface, source, offsetM)
}

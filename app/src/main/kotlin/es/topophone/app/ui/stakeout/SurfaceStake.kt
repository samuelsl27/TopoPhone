package es.topophone.app.ui.stakeout

import es.topophone.core.model.point.ElevationSource
import es.topophone.core.model.point.PointCoordinates
import es.topophone.core.model.point.StakeoutRef
import es.topophone.core.model.surface.SurfaceHeight

/**
 * Lo de la superficie que se guarda con un punto replanteado en el momento de medir (S042/RF-28, RF-29): la cota de la superficie en
 * la posición medida, con el desplazamiento vertical, y de qué superficie.
 *
 * Contra la superficie la cota de diseño es la de la superficie con desplazamiento, o ninguna si la posición está fuera de ella (o su
 * tesela no estaba cargada): entonces no hay cota ni diferencia vertical. Con la cota del punto como origen no se cambia la cota.
 */
class SurfaceStake private constructor(
    private val source: ElevationSource?,
    private val name: String?,
    private val importedAtMs: Long?,
    private val surfaceElevationM: Double?,
    private val offsetM: Double?,
    private val designElevationM: Double?,
    private val replacesElevation: Boolean,
) {
    /** La cota de diseño con la que se mide: la de la superficie contra la superficie (o ninguna si no la hay), y [fallbackM] con la cota del punto. */
    fun elevationOr(fallbackM: Double?): Double? = if (replacesElevation) designElevationM else fallbackM

    /** El replanteo [ref] con el origen de la cota y los datos de la superficie (RF-28); fuera de la superficie, solo su nombre (RF-29). */
    fun applyTo(ref: StakeoutRef): StakeoutRef = ref.copy(
        elevationSource = source,
        surfaceName = name,
        surfaceImportedAtMs = importedAtMs,
        surfaceElevationM = surfaceElevationM,
        verticalOffsetM = offsetM,
    )

    companion object {
        /**
         * Lo que guardar de la superficie con la medida en [measured]: null si el replanteo no es contra la superficie. Con la cota del
         * punto como origen y una superficie activa guarda solo el origen (RF-28); contra la superficie, su cota en la posición medida.
         */
        fun of(info: StakeSaveInfo?, measured: PointCoordinates): SurfaceStake? {
            info ?: return null
            val guide = info.surface ?: return null
            return when (info.elevationSource) {
                null -> null
                ElevationSource.POINT -> SurfaceStake(ElevationSource.POINT, null, null, null, null, null, replacesElevation = false)
                ElevationSource.SURFACE -> {
                    val easting = measured.easting
                    val northing = measured.northing
                    val height = if (easting != null && northing != null) guide.heightAt(easting, northing) else SurfaceHeight.Outside
                    when (height) {
                        is SurfaceHeight.Value -> SurfaceStake(
                            ElevationSource.SURFACE,
                            guide.surface.name,
                            guide.surface.importedAtMs,
                            height.metres,
                            guide.verticalOffsetM,
                            height.metres + guide.verticalOffsetM,
                            replacesElevation = true,
                        )

                        // Fuera de la superficie: sin cota ni diferencia, pero se guarda de qué superficie se salió (RF-29, RF-30).
                        SurfaceHeight.Outside ->
                            SurfaceStake(
                                null,
                                guide.surface.name,
                                guide.surface.importedAtMs,
                                null,
                                guide.verticalOffsetM,
                                null,
                                replacesElevation = true,
                            )

                        // Su tesela aún no estaba cargada: no se sabe, y no se afirma nada de la superficie.
                        SurfaceHeight.Loading -> SurfaceStake(null, null, null, null, null, null, replacesElevation = true)
                    }
                }
            }
        }
    }
}

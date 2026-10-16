package es.topophone.core.model.ntrip

/** Cómo se consigue el usuario de una red pública (S007/RF-12). */
enum class CasterAccess { FREE_REGISTRATION, REGISTRATION, REQUEST, OPEN, PAID }

/**
 * Caster público de España: plantilla para crear un perfil (S007/RF-11). El nombre de la red, su ámbito
 * y su variante son textos de la interfaz, por [id].
 *
 * @property openUsername y [openPassword]: los públicos que da su web, solo si el acceso es libre (S007/RF-13).
 */
data class PublicCaster(
    val id: String,
    val host: String,
    val port: Int,
    val access: CasterAccess,
    val signupUrl: String,
    val useTls: Boolean = false,
    val openUsername: String? = null,
    val openPassword: String? = null,
)

/**
 * Casters públicos de España, comprobados el 16/10/2026 en la web de cada red y con su tabla de fuentes
 * (S007/RF-11 a RF-13; fuentes en `specs/007-ntrip-perfiles-y-sourcetable/plan.md`). Ninguno ofrece TLS.
 * Castilla-La Mancha, Galicia, Madrid, Ceuta y Melilla no tienen caster propio: se usa el del IGN.
 */
object PublicCasters {
    private const val IGN = "https://www.ign.es/web/ign/portal/gds-gnss-tiempo-real"
    private const val RAP = "https://www.juntadeandalucia.es/institutodeestadisticaycartografia/rap/correcciones-diferenciales"
    private const val REP = "https://rep.juntaex.es/gppstartpage_servlet"
    private const val RGAN = "https://www.navarra.es/es/web/rgan/posicionamiento"
    private const val EUSKADI = "https://gis.hazi.eus/webredgnsseuskadi/"
    private const val ANONYMOUS = "anonimo"

    val ALL: List<PublicCaster> = listOf(
        PublicCaster("ign_network", "ergnss-tr.ign.es", 2101, CasterAccess.FREE_REGISTRATION, IGN),
        PublicCaster("ign_station", "ergnss-tr.ign.es", 2102, CasterAccess.FREE_REGISTRATION, IGN),
        PublicCaster("rap_network", "rap.juntadeandalucia.es", 2101, CasterAccess.REQUEST, RAP),
        PublicCaster("rap_station", "rap.juntadeandalucia.es", 2102, CasterAccess.REQUEST, RAP),
        PublicCaster("aragea", "ntrip.aragon.es", 2101, CasterAccess.FREE_REGISTRATION, "https://gnss.aragon.es/altas.php"),
        PublicCaster(
            "rgapa",
            "rgapa.cartografia.asturias.es",
            2101,
            CasterAccess.OPEN,
            "https://rgapa.cartografia.asturias.es/rtk.html",
            openUsername = ANONYMOUS,
            openPassword = ANONYMOUS,
        ),
        PublicCaster(
            "xgaib",
            "xarxagnss.caib.es",
            2101,
            CasterAccess.FREE_REGISTRATION,
            "https://www.caib.es/sites/xgaib/ca/registre_xgaib/",
        ),
        PublicCaster(
            "grafcan",
            "195.53.241.146",
            2101,
            CasterAccess.PAID,
            "https://www.grafcan.es/servicios/red-estaciones-gnss/alta-gnss/",
        ),
        PublicCaster(
            "rgac",
            "gnss.cantabria.es",
            2101,
            CasterAccess.OPEN,
            "https://www.territoriodecantabria.es/cartografia-sig/red-activa-de-estaciones-gnss-de-cantabria/puntos-acceso",
            openUsername = ANONYMOUS,
            openPassword = ANONYMOUS,
        ),
        PublicCaster("itacyl", "ntrip.itacyl.es", 2101, CasterAccess.FREE_REGISTRATION, "https://gnss.itacyl.es/inscripcion"),
        PublicCaster("catnet", "catnet-ip.icgc.cat", 2101, CasterAccess.FREE_REGISTRATION, "https://catnet-ip.icgc.cat/spgic/es"),
        PublicCaster(
            "erva",
            "icverva2.icv.gva.es",
            2101,
            CasterAccess.FREE_REGISTRATION,
            "https://icv.gva.es/es/web/icv-erva/altes-en-rtk",
        ),
        PublicCaster("rep_network", "rep.juntaex.es", 2101, CasterAccess.FREE_REGISTRATION, REP),
        PublicCaster("rep_station", "rep.juntaex.es", 2102, CasterAccess.FREE_REGISTRATION, REP),
        PublicCaster(
            "larioja",
            "195.55.164.9",
            2101,
            CasterAccess.REGISTRATION,
            "https://iderioja.larioja.org/servicios/red-de-estaciones-gnss/informacion",
        ),
        PublicCaster("regam", "147.84.177.82", 2101, CasterAccess.REQUEST, "https://sitmurcia.carm.es/conexion-regam"),
        PublicCaster("rgan_2", "176.12.84.26", 2101, CasterAccess.FREE_REGISTRATION, RGAN),
        PublicCaster("rgan_4", "176.12.84.26", 2102, CasterAccess.FREE_REGISTRATION, RGAN),
        PublicCaster("euskadi_network", "www.gps2.euskadi.eus", 2101, CasterAccess.FREE_REGISTRATION, EUSKADI),
        // El puerto 2102 de Euskadi es CMR/Leica: no sirve al RS3.
        PublicCaster("euskadi_station", "www.gps2.euskadi.eus", 2103, CasterAccess.FREE_REGISTRATION, EUSKADI),
        PublicCaster(
            "bizkaia",
            "80.245.2.245",
            2101,
            CasterAccess.OPEN,
            "https://www.bizkaia.eus/es/servicios-de-correccion-en-tiempo-real",
            openUsername = ANONYMOUS,
            openPassword = "1234",
        ),
    )
}

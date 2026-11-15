package es.topophone.app.ui.web

import es.topophone.core.model.guide.PrivateAddress

/**
 * A qué direcciones puede llegar la vista web del receptor (S043/RF-14, RNF-T4; plan D9): solo a una IP privada o de enlace local y,
 * además, la del receptor que se abrió. Una dirección pública, un nombre, otra IP privada o un enlace con usuario se bloquean, y una
 * redirección a otra dirección no se sigue. El puerto puede cambiar: el panel usa algún otro puerto de la misma IP.
 */
class WebAddressPolicy(private val receiverHost: String) {

    /** ¿Se puede cargar [url]? */
    fun allows(url: String): Boolean = PrivateAddress.allowsUrl(url) && hostOf(url) == receiverHost

    private fun hostOf(url: String): String =
        url.removePrefix("http://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')

    companion object {
        /** La política para la dirección [url] que se abre (`http://<IP>[:puerto]/`), o null si no es una IP privada válida. */
        fun forUrl(url: String): WebAddressPolicy? {
            if (!PrivateAddress.allowsUrl(url)) return null
            return WebAddressPolicy(url.removePrefix("http://").substringBefore('/').substringBefore(':'))
        }
    }
}

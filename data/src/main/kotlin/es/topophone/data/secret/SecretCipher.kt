package es.topophone.data.secret

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Secreto cifrado: el IV y el texto cifrado con su etiqueta GCM. Es lo único que se guarda (RNF-T11). */
class SealedSecret(val iv: ByteArray, val ciphertext: ByteArray)

/** Cifra y descifra secretos (contraseñas NTRIP, S007/RF-4); `null` al descifrar si no se puede (S007/RF-6). */
interface SecretCipher {
    fun encrypt(plain: String): SealedSecret

    fun decrypt(sealed: SealedSecret): String?
}

/** De dónde sale la clave: el Android Keystore en la app, una clave en memoria en los tests. */
fun interface SecretKeyProvider {
    fun key(): SecretKey
}

/** AES-256-GCM con etiqueta de 128 bits; el IV (12 bytes, aleatorio) lo genera el proveedor en cada cifrado. */
class AesGcmCipher(private val keys: SecretKeyProvider) : SecretCipher {

    override fun encrypt(plain: String): SealedSecret {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, keys.key()) }
        return SealedSecret(cipher.iv, cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Clave perdida (móvil restaurado), distinta o datos alterados: no se descifra y se pide de nuevo (S007/RF-6). */
    override fun decrypt(sealed: SealedSecret): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, sealed.iv))
        cipher.doFinal(sealed.ciphertext).toString(Charsets.UTF_8)
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}

/** Clave AES de 256 bits del Android Keystore, que no sale del dispositivo; se crea la primera vez (RNF-T11). */
class AndroidKeystoreKeyProvider(private val alias: String = NTRIP_PASSWORDS) : SecretKeyProvider {

    override fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    companion object {
        const val NTRIP_PASSWORDS = "ntrip_passwords"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_BITS = 256
    }
}

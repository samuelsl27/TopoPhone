package es.topophone.data.secret

import io.kotest.matchers.collections.shouldNotContainInOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.security.KeyStoreException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.junit.Test

/**
 * Cifrado de las contraseñas NTRIP con AES-GCM (S007/RF-4, RF-6; RNF-T11). En la app la clave es del
 * Android Keystore; aquí, una clave en memoria con el mismo algoritmo.
 */
class SecretCipherTest {

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val key = newKey()
    private val cipher = AesGcmCipher { key }
    private val password = "contraseña-€ñ 123"

    @Test
    fun `S007 RF-4 la contrasena cifrada se descifra igual`() {
        cipher.decrypt(cipher.encrypt(password)) shouldBe password
    }

    @Test
    fun `S007 RF-4 lo que se guarda no contiene la contrasena en claro`() {
        val sealed = cipher.encrypt(password)

        sealed.iv.size shouldBe 12
        sealed.ciphertext.size shouldBe password.toByteArray().size + 16 // etiqueta GCM de 128 bits
        sealed.ciphertext.toList() shouldNotContainInOrder password.toByteArray().toList()
    }

    @Test
    fun `S007 RF-4 cada cifrado usa un IV distinto`() {
        val first = cipher.encrypt(password)
        val second = cipher.encrypt(password)

        first.iv.toList() shouldNotBe second.iv.toList()
        first.ciphertext.toList() shouldNotBe second.ciphertext.toList()
    }

    @Test
    fun `S007 RF-6 con otra clave, como tras restaurar el movil, no se descifra`() {
        val sealed = cipher.encrypt(password)

        AesGcmCipher { newKey() }.decrypt(sealed).shouldBeNull()
    }

    @Test
    fun `S007 RF-6 un cifrado alterado no se descifra`() {
        val sealed = cipher.encrypt(password)
        val tampered = SealedSecret(sealed.iv, sealed.ciphertext.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })

        cipher.decrypt(tampered).shouldBeNull()
    }

    @Test
    fun `S007 RF-6 si la clave no esta disponible no se descifra`() {
        val sealed = cipher.encrypt(password)

        AesGcmCipher { throw KeyStoreException("sin Keystore") }.decrypt(sealed).shouldBeNull()
    }

    @Test
    fun `S007 RF-4 la contrasena vacia tambien se cifra y se descifra`() {
        cipher.decrypt(cipher.encrypt("")) shouldBe ""
    }
}

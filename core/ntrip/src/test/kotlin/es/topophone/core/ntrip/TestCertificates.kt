package es.topophone.core.ntrip

import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Certificados autofirmados para probar TLS (S007/RF-27, RF-28). El repositorio no admite ficheros de
 * claves (`tools/repo/forbidden_files.py`), así que se generan al ejecutar los tests con el `keytool`
 * de la propia JVM, en una carpeta temporal:
 * - `local`: válido para 127.0.0.1;
 * - `other`: válido solo para otro.example.
 * El almacén de confianza del cliente contiene los dos.
 */
object TestCertificates {
    private const val PASSWORD = "topophone-test"

    private val dir: File by lazy {
        Files.createTempDirectory("topophone-tls").toFile().also { dir ->
            Runtime.getRuntime().addShutdownHook(Thread { dir.deleteRecursively() })
            generate(dir, "local", "CN=127.0.0.1", "ip:127.0.0.1")
            generate(dir, "other", "CN=otro.example", "dns:otro.example")
            listOf("local", "other").forEach { alias ->
                keytool(dir, "-exportcert", "-alias", alias, "-keystore", "$alias.p12", "-storepass", PASSWORD, "-file", "$alias.cer")
                keytool(
                    dir,
                    "-importcert", "-noprompt", "-alias", alias, "-file", "$alias.cer",
                    "-keystore", "trust.p12", "-storepass", PASSWORD,
                )
            }
        }
    }

    /** Fábrica de sockets de servidor con el certificado [alias] (`local` u `other`). */
    fun server(alias: String): SSLServerSocketFactory {
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(load("$alias.p12"), PASSWORD.toCharArray())
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
    }

    /** Fábrica de sockets de cliente que confía en los dos certificados de prueba. */
    fun trustingClient(): SSLSocketFactory {
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(load("trust.p12"))
        return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }.socketFactory
    }

    private fun load(name: String): KeyStore = KeyStore.getInstance("PKCS12").apply {
        File(dir, name).inputStream().use { load(it, PASSWORD.toCharArray()) }
    }

    private fun generate(dir: File, alias: String, subject: String, san: String) = keytool(
        dir,
        "-genkeypair", "-alias", alias, "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "3650",
        "-dname", subject, "-ext", "san=$san", "-keystore", "$alias.p12", "-storetype", "PKCS12",
        "-storepass", PASSWORD, "-keypass", PASSWORD,
    )

    private fun keytool(dir: File, vararg args: String) {
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        val process = ProcessBuilder(keytool, *args).directory(dir).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "keytool ${args.first()}: $output" }
    }
}

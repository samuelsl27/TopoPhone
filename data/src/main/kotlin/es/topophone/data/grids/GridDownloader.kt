package es.topophone.data.grids

import es.topophone.core.geodesy.catalog.GridEntry
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** La respuesta a un GET de una rejilla: el código HTTP, el tamaño que anuncia (−1 si no lo dice) y el cuerpo, que solo se lee dentro de [GridHttp.get]. */
class GridHttpResponse(val code: Int, val contentLength: Long, val body: InputStream)

/** El acceso a la red para bajar rejillas (S034/RF-19, RNF-T4, ADR-13); es una interfaz para probar con respuestas grabadas. */
interface GridHttp {
    /** Pide [url] y llama a [read] con la respuesta, que se cierra al volver. Lanza [IOException] si no hay conexión o se corta. */
    suspend fun <T> get(url: String, read: (GridHttpResponse) -> T): T
}

/** [GridHttp] con OkHttp (ADR-13), por la red con internet y sin límite de tiempo total: una rejilla puede pesar decenas de MB. */
class OkHttpGridHttp(private val client: OkHttpClient) : GridHttp {
    override suspend fun <T> get(url: String, read: (GridHttpResponse) -> T): T = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder().url(url).build()
        } catch (error: IllegalArgumentException) {
            throw IOException("La dirección no es válida", error)
        }
        val call = client.newCall(request)
        val registration = currentCoroutineContext().job.invokeOnCompletion { if (it != null) call.cancel() }
        try {
            call.execute().use { response ->
                val body = response.body ?: throw IOException("Respuesta sin cuerpo")
                read(GridHttpResponse(response.code, body.contentLength(), body.byteStream()))
            }
        } finally {
            registration.dispose()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S = 30L

        fun create(userAgent: String): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
            .followSslRedirects(false) // una dirección HTTPS nunca baja a HTTP
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build()) }
            .build()
    }
}

/** Cómo acaba una descarga (S034/RF-21, RF-22). */
sealed interface DownloadResult {
    /** La rejilla está instalada y su SHA-256 es el de la biblioteca. */
    data object Done : DownloadResult

    /** La descarga se cortó o llegó incompleta: no queda nada a medias y se puede reintentar (RF-22). */
    data class Interrupted(val cause: String? = null) : DownloadResult

    /** El fichero no tiene el SHA-256 de la biblioteca: se descarta y no se instala (RF-21). */
    data object Damaged : DownloadResult

    /** El servidor respondió con un error. */
    data class ServerError(val code: Int) : DownloadResult
}

/**
 * Descarga una rejilla del CDN de PROJ a [store], de forma atómica (S034/RF-19, RF-21, RF-22): se baja a un fichero `.part`, se comprueba que
 * tiene el tamaño y el SHA-256 de la biblioteca y solo entonces se renombra. Si algo falla o se cancela, el `.part` se borra. La red y la
 * confirmación del usuario (RF-19) son cosa de quien llama.
 */
class GridDownloader(private val store: GridStore, private val http: GridHttp, private val baseUrl: String = DEFAULT_BASE_URL) {
    init {
        require(baseUrl.startsWith("https://")) { "Las rejillas se descargan solo por HTTPS" }
    }

    /** @param onProgress los bytes descargados hasta ahora, con la rejilla entera como [GridEntry.sizeBytes]. */
    suspend fun download(entry: GridEntry, onProgress: (downloaded: Long) -> Unit = {}): DownloadResult {
        val part = store.partFileOf(entry.name) ?: return DownloadResult.Interrupted("Nombre de rejilla no válido")
        val target = store.fileOf(entry.name) ?: return DownloadResult.Interrupted("Nombre de rejilla no válido")
        part.parentFile?.mkdirs()
        part.delete()
        var finished = false
        val job = currentCoroutineContext().job
        try {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    http.get(baseUrl + entry.name) { response -> receive(entry, response, part, job, onProgress) }
                } catch (error: IOException) {
                    DownloadResult.Interrupted(error.message)
                }
            }
            if (outcome != null) return outcome
            // Renombrar es atómico: o está la rejilla entera o no está (RF-22).
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            finished = true
            store.changed(entry.name)
            return DownloadResult.Done
        } catch (error: IOException) {
            return DownloadResult.Interrupted(error.message)
        } finally {
            if (!finished) part.delete()
        }
    }

    /** Baja el cuerpo al fichero `.part` y lo comprueba; devuelve el motivo del fallo o `null` si está bien y listo para renombrar. */
    private fun receive(
        entry: GridEntry,
        response: GridHttpResponse,
        part: java.io.File,
        job: Job,
        onProgress: (Long) -> Unit,
    ): DownloadResult? {
        if (response.code != HTTP_OK) return DownloadResult.ServerError(response.code)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val buffer = ByteArray(BUFFER_BYTES)
        part.outputStream().use { out ->
            while (true) {
                job.ensureActive()
                val read = response.body.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                digest.update(buffer, 0, read)
                total += read
                onProgress(total)
            }
            out.fd.sync()
        }
        if (total != entry.sizeBytes) return DownloadResult.Interrupted("Llegaron $total de ${entry.sizeBytes} bytes")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        return if (sha.equals(entry.sha256, ignoreCase = true)) null else DownloadResult.Damaged
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://cdn.proj.org/"
        private const val HTTP_OK = 200
        private const val BUFFER_BYTES = 64 * 1024
    }
}

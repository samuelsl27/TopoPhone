package es.topophone.app.ui.collector

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import es.topophone.app.ui.receiver.fileProviderAuthority
import es.topophone.core.model.point.PhotoPolicy
import java.io.File

/** Lo que lanza «Foto» y «Galería» del recolector (S017b/RF-1). */
class CollectorPhotoLaunchers(val camera: () -> Unit, val gallery: () -> Unit)

/**
 * La cámara del sistema (sin permiso `CAMERA`: la foto la hace su app) y el selector de fotos del sistema (sin permiso de lectura de
 * medios), con hasta las 10 fotos de un punto (plan, «Cámara y galería»). Cerrar cualquiera sin elegir no cambia nada (RF-16).
 */
@Composable
fun rememberCollectorPhotoLaunchers(photos: CollectorPhotos): CollectorPhotoLaunchers {
    val context = LocalContext.current
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken -> photos.finishCapture(taken) }
    val pick = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(PhotoPolicy.MAX_PER_POINT),
    ) { uris -> photos.addFromGallery(uris.map { it.toString() }) }
    return remember(photos, context) {
        CollectorPhotoLaunchers(
            camera = {
                photos.beginCapture()?.let { path ->
                    take.launch(FileProvider.getUriForFile(context, fileProviderAuthority(context), File(path)))
                }
            },
            gallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

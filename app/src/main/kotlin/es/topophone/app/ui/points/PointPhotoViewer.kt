package es.topophone.app.ui.points

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import es.topophone.app.R
import es.topophone.app.ui.photos.PhotoImages
import es.topophone.app.ui.projects.formatDateTime
import es.topophone.app.ui.receiver.uiLocale
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.point.PhotoOrigin
import es.topophone.core.model.point.PointPhoto
import java.util.Locale
import kotlinx.coroutines.launch

const val PHOTO_VIEWER_TAG = "photo_viewer"
const val PHOTO_VIEWER_POSITION_TAG = "photo_viewer_position"
const val PHOTO_VIEWER_INFO_TAG = "photo_viewer_info"
const val PHOTO_VIEWER_PREVIOUS_TAG = "photo_viewer_previous"
const val PHOTO_VIEWER_NEXT_TAG = "photo_viewer_next"
const val PHOTO_VIEWER_CLOSE_TAG = "photo_viewer_close"
const val PHOTO_VIEWER_DELETE_TAG = "photo_viewer_delete"
const val PHOTO_VIEWER_UNREADABLE_TAG = "photo_viewer_unreadable"

private const val MAX_ZOOM = 5f

/**
 * La foto a pantalla completa, con zoom y paso a las otras fotos del punto (S017b/RF-10), y con su fecha y hora, si es de cámara o de
 * galería y su tamaño (RF-11). «Foto 2 de 5» se anuncia al pasar (RNF-T8). Un fichero que no se puede abrir se sustituye por su aviso
 * (RF-17). [onDelete] es «Borrar» de la foto abierta; null: sin ese botón.
 */
@Composable
internal fun PointPhotoViewer(
    photos: List<PointPhoto>,
    startIndex: Int,
    images: PhotoImages,
    onClose: () -> Unit,
    onDelete: ((PointPhoto) -> Unit)? = null,
) {
    if (photos.isEmpty()) return
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    val scope = rememberCoroutineScope()
    val page = pager.currentPage.coerceIn(0, photos.lastIndex)
    val current = photos[page]
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize().testTag(PHOTO_VIEWER_TAG), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth(), key = { photos[it].id }) { index ->
                    ViewerPage(photos[index], images)
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(R.string.photo_viewer_position, page + 1, photos.size),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.testTag(PHOTO_VIEWER_POSITION_TAG).semantics { liveRegion = LiveRegionMode.Polite }, // RNF-T8
                    )
                    Text(
                        photoInfo(current),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag(PHOTO_VIEWER_INFO_TAG),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(page - 1) } },
                        enabled = page > 0,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PHOTO_VIEWER_PREVIOUS_TAG),
                    ) { Text(stringResource(R.string.photo_viewer_previous)) }
                    TextButton(
                        onClick = { scope.launch { pager.animateScrollToPage(page + 1) } },
                        enabled = page < photos.lastIndex,
                        modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PHOTO_VIEWER_NEXT_TAG),
                    ) { Text(stringResource(R.string.photo_viewer_next)) }
                    Box(modifier = Modifier.weight(1f))
                    if (onDelete != null) {
                        TextButton(
                            onClick = { onDelete(current) },
                            modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PHOTO_VIEWER_DELETE_TAG),
                        ) { Text(stringResource(R.string.photo_viewer_delete)) }
                    }
                    TextButton(onClick = onClose, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(PHOTO_VIEWER_CLOSE_TAG)) {
                        Text(stringResource(R.string.collector_close))
                    }
                }
            }
        }
    }
}

/** Fecha y hora, origen y tamaño de la foto abierta (RF-11). */
@Composable
private fun photoInfo(photo: PointPhoto): String {
    val origin = stringResource(if (photo.origin == PhotoOrigin.CAMERA) R.string.photo_origin_camera else R.string.photo_origin_gallery)
    return stringResource(
        R.string.photo_viewer_info,
        photo.name,
        formatDateTime(photo.takenAtMs, uiLocale()),
        origin,
        fileSizeText(photo.sizeBytes, uiLocale()),
    )
}

/** El tamaño de un fichero: «850 kB» o «1,2 MB». */
internal fun fileSizeText(bytes: Long, locale: Locale): String = if (bytes <
    1_000_000L
) {
    String.format(locale, "%d kB", Math.round(bytes / 1_000.0))
} else {
    String.format(locale, "%.1f MB", bytes / 1_000_000.0)
}

@Composable
private fun ViewerPage(photo: PointPhoto, images: PhotoImages) {
    val side = LocalContext.current.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }.coerceAtLeast(1)
    val loaded by produceState<Loaded>(Loaded.Loading, photo.id) {
        value = images.full(photo.id, side)?.let { Loaded.Ready(it) } ?: Loaded.Unreadable
    }
    when (val state = loaded) {
        Loaded.Loading -> Box(Modifier.fillMaxSize())
        Loaded.Unreadable -> Box(Modifier.fillMaxSize().testTag(PHOTO_VIEWER_UNREADABLE_TAG), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(photo.name, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.point_photo_unreadable), color = MaterialTheme.colorScheme.error)
            }
        }
        is Loaded.Ready -> ZoomableImage(state.bitmap)
    }
}

private sealed interface Loaded {
    data object Loading : Loaded

    data class Ready(val bitmap: Bitmap) : Loaded

    data object Unreadable : Loaded
}

/** La foto con zoom de pellizco (hasta 5×); con zoom se mueve con un dedo y sin él ese gesto pasa a la foto de al lado. */
@Composable
private fun ZoomableImage(bitmap: Bitmap) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    LaunchedEffect(bitmap) {
        scale = 1f
        offset = Offset.Zero
    }
    Image(
        bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize()
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
            .transformable(state, canPan = { scale > 1f }),
    )
}

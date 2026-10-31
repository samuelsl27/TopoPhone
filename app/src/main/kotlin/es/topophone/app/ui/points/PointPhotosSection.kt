package es.topophone.app.ui.points

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.app.ui.photos.PhotoImages
import es.topophone.core.model.point.PointPhoto

const val POINT_PHOTOS_TAG = "point_photos"

fun photoThumbnailTag(index: Int) = "point_photo_$index"

fun photoUnreadableTag(index: Int) = "point_photo_unreadable_$index"

/** Lo que se ve de una miniatura: cargando, cargada o un fichero que no se puede abrir (S017b/RF-9, RF-17). */
private sealed interface Thumbnail {
    data object Loading : Thumbnail

    data class Loaded(val bitmap: android.graphics.Bitmap) : Thumbnail

    data object Unreadable : Thumbnail
}

/**
 * La sección «Fotos» de la ficha de un punto, medido o manual (S017b/RF-9): las miniaturas en el orden en que se añadieron, cada una con
 * su número y «Abrir» para TalkBack (RNF-T8). La de un fichero que no se puede abrir lleva su nombre y un aviso, sin afectar a las demás
 * (RF-17). [onOpen] recibe la posición de la foto tocada.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PointPhotosSection(
    pointName: String,
    photos: List<PointPhoto>,
    images: PhotoImages,
    onOpen: (Int) -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    // Sin fusionar los descendientes: cada miniatura es una zona táctil con su propio texto para TalkBack (RNF-T8).
    Card(modifier = modifier.fillMaxWidth().testTag(POINT_PHOTOS_TAG)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.point_photos_title), style = MaterialTheme.typography.titleMedium)
            if (photos.isNotEmpty()) {
                Text(
                    pluralStringResource(R.plurals.point_photos_count, photos.size, photos.size),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEachIndexed { index, photo -> PhotoThumbnail(pointName, index, photos.size, photo, images) { onOpen(index) } }
            }
            actions()
        }
    }
}

@Composable
private fun PhotoThumbnail(pointName: String, index: Int, total: Int, photo: PointPhoto, images: PhotoImages, onOpen: () -> Unit) {
    val state by produceState<Thumbnail>(Thumbnail.Loading, photo.id) {
        value = images.thumbnail(photo.id)?.let { Thumbnail.Loaded(it) } ?: Thumbnail.Unreadable
    }
    val description = stringResource(R.string.point_photo_description, index + 1, total, pointName)
    val openLabel = stringResource(R.string.point_photo_open)
    Box(
        modifier = Modifier.size(THUMB_DP.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant)
            .clickable(onClickLabel = openLabel, onClick = onOpen)
            .semantics { contentDescription = description } // RNF-T8: «Foto 2 de 5 del punto 12»
            .testTag(photoThumbnailTag(index)),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            Thumbnail.Loading -> Unit
            is Thumbnail.Loaded -> Image(
                s.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Thumbnail.Unreadable -> Column(
                modifier = Modifier.padding(4.dp).testTag(photoUnreadableTag(index)),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(photo.name, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                Text(
                    stringResource(R.string.point_photo_unreadable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private const val THUMB_DP = 96

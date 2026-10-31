package es.topophone.app.ui.collector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import es.topophone.app.R
import es.topophone.core.designsystem.component.MinTouchTarget
import es.topophone.core.model.point.PhotoDestination
import es.topophone.core.model.point.PhotoPolicy
import es.topophone.data.photo.PhotoFailure

const val COLLECTOR_PHOTO_TAG = "collector_photo"
const val COLLECTOR_GALLERY_TAG = "collector_gallery"
const val COLLECTOR_PHOTO_DESTINATION_TAG = "collector_photo_destination"
const val COLLECTOR_PHOTO_ERROR_TAG = "collector_photo_error"
const val COLLECTOR_PHOTO_DISCARD_TAG = "collector_photo_discard"
const val COLLECTOR_PHOTO_KEEP_TAG = "collector_photo_keep"

/**
 * «Foto» y «Galería» del recolector con a qué punto irá la foto y cuántas lleva (S017b/RF-1, RF-7, RF-8), los avisos de límite y de error
 * (RF-13 a RF-15) y la pregunta de descartar las fotos al cancelar la medida (RF-3). Con una zona táctil de 56 dp (RNF-T8).
 */
@Composable
internal fun CollectorPhotoBar(photos: PhotoUi, actions: CollectorActions) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = actions.onPhoto,
                enabled = photos.canAdd,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_PHOTO_TAG),
            ) { Text(stringResource(R.string.photo_button)) }
            OutlinedButton(
                onClick = actions.onGallery,
                enabled = photos.canAdd,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget).testTag(COLLECTOR_GALLERY_TAG),
            ) { Text(stringResource(R.string.photo_gallery_button)) }
        }
        val destination = if (photos.destination == PhotoDestination.None || photos.destinationName == null) {
            stringResource(R.string.photo_no_destination) // RF-7: el motivo de los botones desactivados
        } else {
            stringResource(R.string.photo_destination, photos.destinationName, photos.count, PhotoPolicy.MAX_PER_POINT) // RF-8
        }
        Text(destination, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(COLLECTOR_PHOTO_DESTINATION_TAG))
        photos.error?.let { error ->
            Text(
                error.text(),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(COLLECTOR_PHOTO_ERROR_TAG).semantics { liveRegion = LiveRegionMode.Polite }, // RNF-T8
            )
        }
    }
    photos.pendingCancel?.let { count -> DiscardPhotosDialog(count, actions.onDiscardPhotos, actions.onKeepPhotos) }
}

@Composable
internal fun PhotoError.text(): String = when (this) {
    PhotoError.Full -> stringResource(R.string.photo_limit)
    is PhotoError.NotAllFit -> pluralStringResource(R.plurals.photo_not_all_fit, count, count)
    is PhotoError.Failed -> stringResource(
        R.string.photo_failed,
        stringResource(
            when (reason) {
                PhotoFailure.UNREADABLE -> R.string.photo_failed_unreadable
                PhotoFailure.NO_SPACE -> R.string.photo_failed_no_space
                PhotoFailure.WRITE_FAILED -> R.string.photo_failed_write
            },
        ),
    )
}

/** RF-3: cancelar una medida con fotos pregunta cuántas se descartan. */
@Composable
private fun DiscardPhotosDialog(count: Int, onDiscard: () -> Unit, onKeep: () -> Unit) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text(pluralStringResource(R.plurals.photo_discard_title, count, count)) },
        text = { Text(stringResource(R.string.photo_discard_text)) },
        confirmButton = {
            TextButton(onClick = onDiscard, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_PHOTO_DISCARD_TAG)) {
                Text(stringResource(R.string.photo_discard_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep, modifier = Modifier.heightIn(min = MinTouchTarget).testTag(COLLECTOR_PHOTO_KEEP_TAG)) {
                Text(stringResource(R.string.photo_keep))
            }
        },
    )
}

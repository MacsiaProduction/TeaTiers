package com.macsia.teatiers.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.macsia.teatiers.R

/**
 * "Camera or gallery" chooser (UX2-P1-11's convention): two equal, non-hierarchical choices as
 * full-width rows in the body, not overloaded onto confirm/dismiss — dismissButton is a real
 * Cancel. Shared by OCR scan capture (AddTeaScreen) and photo capture (PhotoStrip, UX2-F-1/F-2),
 * which had each duplicated this ~45-line dialog independently (post-merge review).
 */
@Composable
fun SourceChooserDialog(
    title: String,
    onDismissRequest: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title) },
        text = {
            Column {
                SourceChooserRow(stringResource(R.string.ocr_scan_source_camera), onCamera)
                SourceChooserRow(stringResource(R.string.ocr_scan_source_gallery), onGallery)
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SourceChooserRow(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 14.dp, horizontal = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
        )
    }
}

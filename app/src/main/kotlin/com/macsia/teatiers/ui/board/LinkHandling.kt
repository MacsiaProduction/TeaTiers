package com.macsia.teatiers.ui.board

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import com.macsia.teatiers.R

/**
 * Open [url] in the browser. If no app can handle it (rare on stock devices), tell the user with a
 * toast instead of silently doing nothing — a tapped link that does nothing reads as broken.
 */
fun Context.openExternalUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        .onFailure { Toast.makeText(this, R.string.error_cannot_open_link, Toast.LENGTH_SHORT).show() }
}

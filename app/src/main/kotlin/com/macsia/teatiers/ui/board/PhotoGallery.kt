package com.macsia.teatiers.ui.board

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.macsia.teatiers.R
import com.macsia.teatiers.domain.model.TeaPhoto

private val GalleryHeight = 240.dp
private val DotSize = 8.dp

/**
 * Detail-screen photo gallery (decisions.md #43): horizontal pager of full-width thumbnails
 * with a dot indicator. Tapping any photo opens [PhotoZoomDialog] which supports pinch / pan /
 * double-tap-to-reset, mirroring the standard Material gallery behaviour.
 *
 * The zoom dialog inherits the pager's current page so a photo opened from page 2 keeps that
 * context; swiping inside the dialog moves the dialog's own pager but leaves the parent's page
 * alone (cheaper than wiring the same pager state through, and the user expectation is just
 * "swipe between full-screen pictures").
 */
@Composable
fun PhotoGallery(photos: List<TeaPhoto>, modifier: Modifier = Modifier) {
    if (photos.isEmpty()) return

    val pagerState = rememberPagerState(pageCount = { photos.size })
    var zoomStartIndex by remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier.fillMaxWidth().height(GalleryHeight)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val photo = photos[page]
            Surface(
                onClick = { zoomStartIndex = page },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                PhotoImage(uri = photo.uri, modifier = Modifier.fillMaxSize())
            }
        }
        if (photos.size > 1) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp),
            ) {
                androidx.compose.foundation.layout.Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    val activeColor = MaterialTheme.colorScheme.primary
                    val inactiveColor = MaterialTheme.colorScheme.outlineVariant
                    repeat(photos.size) { index ->
                        val on = index == pagerState.currentPage
                        // The active dot stretches into a pill so the indicator slides between
                        // pages instead of step-toggling between two static dots.
                        val width by animateDpAsState(
                            targetValue = if (on) DotSize * 2.25f else DotSize,
                            animationSpec = spring(stiffness = 700f),
                            label = "dot-width",
                        )
                        val color by animateColorAsState(
                            targetValue = if (on) activeColor else inactiveColor,
                            animationSpec = spring(stiffness = 700f),
                            label = "dot-color",
                        )
                        Box(
                            modifier = Modifier
                                .height(DotSize)
                                .width(width)
                                .clip(CircleShape)
                                .background(color),
                        )
                    }
                }
            }
        }
    }

    zoomStartIndex?.let { startIndex ->
        PhotoZoomDialog(
            photos = photos,
            startIndex = startIndex,
            onDismiss = { zoomStartIndex = null },
        )
    }
}

/**
 * Full-screen pinch-zoom dialog. The pager carries the current photo, [transformable] applies
 * the user's pan/scale, and double-tap snaps back to fit. Outside the dialog the parent's
 * pager keeps its own page so closing the dialog drops the user back where they were.
 */
@Composable
private fun PhotoZoomDialog(photos: List<TeaPhoto>, startIndex: Int, onDismiss: () -> Unit) {
    val pagerState = rememberPagerState(initialPage = startIndex, pageCount = { photos.size })
    // AnimatedVisibility flipped from false -> true after first composition gives the dialog a
    // fade + scale entrance instead of the platform-default cut. The Dialog itself still owns
    // the dismiss path, which plays Android's standard exit transition.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = spring(stiffness = 600f)) +
                scaleIn(initialScale = 0.96f, animationSpec = spring(stiffness = 600f)),
            exit = fadeOut() + scaleOut(targetScale = 0.96f),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    ZoomablePhoto(uri = photos[page].uri)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.a11y_photo_zoom_close),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomablePhoto(uri: String) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offsetX by remember(uri) { mutableFloatStateOf(0f) }
    var offsetY by remember(uri) { mutableFloatStateOf(0f) }
    // Track the current scale outside the lambda passed to rememberTransformableState because
    // the lambda is captured once and would otherwise read the initial value forever. The
    // 3-arg overload is deprecated in favour of one that hands us the centroid; the 3-arg form
    // is fine for fit-to-screen pinch zoom and the 4-arg overload is not yet stable.
    @Suppress("DEPRECATION")
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        offsetX += panChange.x
        offsetY += panChange.y
    }
    val resetCount = remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(uri, resetCount.intValue) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offsetX = 0f
                    offsetY = 0f
                    resetCount.intValue++
                })
            }
            .transformable(state)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                translationY = offsetY
            },
        contentAlignment = Alignment.Center,
    ) {
        PhotoImage(uri = uri, modifier = Modifier.fillMaxSize())
    }
}


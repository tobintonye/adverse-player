package com.adverse.adverseplayer.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.adverse.adverseplayer.storage.CachedPlaylistItem
import com.adverse.adverseplayer.sync.SyncService

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

/**
 * Reads ONLY from local disk (item.localPath) — never a network URL. That's
 * the whole point of the local cache: a dead connection never blanks the
 * billboard, it just keeps looping the last known-good playlist.
 *
 * Every item is forced to respect its server-assigned durationSeconds:
 *  - Video: clipped to that end position, even if the file itself is longer
 *    (an advertiser's raw upload might not exactly match the slot they paid
 *    for — the schedule, not the file, is the source of truth for timing)
 *  - Image: has no natural length, so setImageDurationMs is what makes
 *    ExoPlayer advance to the next item at all
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(items: List<CachedPlaylistItem>, context: Context) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No active content scheduled", color = Color.DarkGray)
        }
        return
    }

    val localContext = LocalContext.current
    var currentIndex by remember(items) { mutableIntStateOf(0) }

    val exoPlayer = remember(items) {
        ExoPlayer.Builder(localContext).build().apply {
            items.forEach { item ->
                item.localPath?.let { path ->
                    addMediaItem(buildMediaItem(path, item.durationSeconds))
                }
            }
            repeatMode = Player.REPEAT_MODE_ALL
            prepare()
            play()

            addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // A transition means the previous item hit its assigned
                    // duration (or the video ended naturally, if shorter) —
                    // log it as a completed play-through and advance.
                    val finishedIndex = currentIndex
                    if (finishedIndex in items.indices) {
                        SyncService.logPlayback(context, items[finishedIndex], completed = true)
                    }
                    currentIndex = (currentIndex + 1) % items.size
                }
            })
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false // unattended kiosk — no playback controls needed
            }
        }
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
private fun buildMediaItem(localPath: String, durationSeconds: Int): MediaItem {
    val uri = Uri.fromFile(java.io.File(localPath))
    val ext = localPath.substringAfterLast('.', "").lowercase()
    val durationMs = durationSeconds.toLong() * 1000L

    val builder = MediaItem.Builder().setUri(uri)

    return if (ext in IMAGE_EXTENSIONS) {
        // Images have no inherent playback length — this is what makes
        // ExoPlayer treat it as a timed slide rather than an instant no-op.
        builder.setImageDurationMs(durationMs).build()
    } else {
        // Video: clip to the assigned slot duration so a longer raw upload
        // can't overrun what the advertiser actually paid for.
        builder.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setEndPositionMs(durationMs)
                .build()
        ).build()
    }
}

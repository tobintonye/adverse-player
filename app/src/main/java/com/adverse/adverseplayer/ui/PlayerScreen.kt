package com.adverse.adverseplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.adverse.adverseplayer.storage.CachedPlaylistItem
import com.adverse.adverseplayer.sync.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

// Fallback if a scheduled item somehow has a non-positive duration — keeps an
// image from flashing for 0s or hanging on screen forever with nothing driving it.
private const val DEFAULT_IMAGE_DISPLAY_SECONDS = 8

private fun currentSecondsOfDay(): Int {
    val cal = Calendar.getInstance()
    return cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
}

/**
 * Clock-driven, not self-timed. A single 1-second ticker decides what's due
 * right now — "the most recently-started slot whose scheduled_time has
 * already arrived" — and switches the instant the NEXT slot's time hits.
 *
 * Deliberately does NOT watch each item's own duration_seconds to decide
 * when to advance SLOTS. duration_seconds comes from the advertiser's real
 * media length, which can be longer OR SHORTER than the grid spacing the
 * seat-booking system assumed — switching on the next slot's START time
 * rather than this one's END time makes back-to-back overlap physically
 * impossible, regardless of any mismatch between assumed and real durations.
 *
 * What duration_seconds DOES drive now: how long the media actually plays
 * before the screen goes idle. A slot that's due for hours (sparse daily
 * schedule) no longer loops its media for the entire gap — it plays once,
 * then holds an honest black screen until the next slot's time arrives.
 * This keeps on-screen airtime matched to what was actually booked/billed
 * (a fixed slot, not "however long until the next advertiser's slot").
 *
 * No slot replays within the same day: once its window has passed, nothing
 * in the cached list matches "now" again until tomorrow's real schedule
 * pull replaces it — this falls out of the clock comparison for free, no
 * extra "already played today" bookkeeping needed.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun PlayerScreen(items: List<CachedPlaylistItem>, context: Context) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No active content scheduled", color = Color.DarkGray)
        }
        return
    }

    var currentItem by remember(items) { mutableStateOf<CachedPlaylistItem?>(null) }
    // True once the current item has finished playing naturally (video ended /
    // image shown for its full duration) and is just holding black until the
    // next slot's scheduled_time arrives. Reset whenever currentItem changes.
    var playbackFinished by remember(items) { mutableStateOf(false) }
    // True once we've already logged completion for the current item, so the
    // transition logic below doesn't double-log or wrongly log an interrupted
    // play as completed.
    var completionLogged by remember(items) { mutableStateOf(false) }

    LaunchedEffect(items) {
        while (isActive) {
            val now = currentSecondsOfDay()
            // items is already sorted by scheduledSecondsOfDay (see PlaylistDao)
            val due = items.lastOrNull { it.scheduledSecondsOfDay <= now }

            if (due?.timeSlotId != currentItem?.timeSlotId) {
                // A transition happened — the next slot's time arrived.
                val outgoing = currentItem
                if (outgoing != null && !completionLogged) {
                    // Playback was still going (or never started) when the next
                    // slot's time hit — this is a genuine interruption, not a
                    // completed play. Log it honestly rather than as completed.
                    SyncService.logPlayback(context, outgoing, completed = false)
                }
                currentItem = due
                playbackFinished = false
                completionLogged = false
            }
            delay(1000)
        }
    }

    val item = currentItem
    if (item == null || playbackFinished) {
        // Either before the day's first scheduled slot / in a genuine gap, or
        // this slot's media already finished playing and we're honestly
        // holding black until the next slot's time arrives — no filler-content
        // system exists, so both cases render the same idle black screen.
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
    } else {
        val isImage = item.localPath
            ?.substringAfterLast('.', "")
            ?.lowercase() in IMAGE_EXTENSIONS

        val onComplete: () -> Unit = {
            if (!completionLogged) {
                SyncService.logPlayback(context, item, completed = true)
                completionLogged = true
            }
            playbackFinished = true
        }
        val onError: () -> Unit = {
            if (!completionLogged) {
                // Distinct from onComplete: this slot never actually played
                // successfully, so it's logged as an honest failure rather
                // than a completed play. Still goes idle the same way so the
                // coordinator isn't left stuck waiting on a dead player.
                SyncService.logPlayback(context, item, completed = false)
                completionLogged = true
            }
            playbackFinished = true
        }

        if (isImage) {
            ImageSlide(item, onComplete)
        } else {
            VideoSlide(item, onComplete, onError)
        }
    }
}

@Composable
private fun ImageSlide(item: CachedPlaylistItem, onComplete: () -> Unit) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = item.localPath
    ) {
        value = withContext(Dispatchers.IO) {
            item.localPath?.let { path ->
                runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
            }
        }
    }

    // Show the image for its scheduled duration, then signal completion so
    // the parent switches to the idle black screen instead of holding this
    // image on screen indefinitely.
    LaunchedEffect(item.timeSlotId) {
        val displaySeconds = if (item.durationSeconds > 0) item.durationSeconds else DEFAULT_IMAGE_DISPLAY_SECONDS
        delay(displaySeconds * 1000L)
        onComplete()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun VideoSlide(item: CachedPlaylistItem, onComplete: () -> Unit, onError: () -> Unit) {
    val localContext = LocalContext.current

    // No local file at all (download never completed) — nothing to play.
    // Report this the same way as a playback error rather than silently
    // starting an empty ExoPlayer that would never fire onPlayerError.
    if (item.localPath == null) {
        LaunchedEffect(item.timeSlotId) { onError() }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val exoPlayer = remember(item.timeSlotId) {
        ExoPlayer.Builder(localContext).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(item.localPath!!))))
            // Play once and stop — REPEAT_MODE_OFF is the default, kept
            // explicit here so the intent is obvious. The coordinator above
            // still decides when to switch AWAY from this slot (on the next
            // slot's start time); this only decides when the media itself
            // finishes, so we can go idle instead of looping until switched.
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onComplete()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Corrupt file, unsupported codec, missing localPath, etc.
                    // Without this, a failing video just sits in an error state
                    // forever with no signal anywhere that the slot didn't
                    // actually play — silent on screen AND silent in the logs.
                    // Log it as an honest failed play, then go idle same as a
                    // normal completion so we don't get stuck.
                    onError()
                }
            })
            prepare()
            play()
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
                useController = false
            }
        }
    )
}

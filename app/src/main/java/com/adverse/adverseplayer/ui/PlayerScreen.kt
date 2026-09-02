package com.adverse.adverseplayer.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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

// How long the dissolve between ads takes — matches typical real billboard
// controller crossfade timing (roughly half a second to a second).
private const val TRANSITION_DURATION_MS = 700

private fun currentSecondsOfDay(): Int {
    val cal = Calendar.getInstance()
    return cal.get(Calendar.HOUR_OF_DAY) * 3600 + cal.get(Calendar.MINUTE) * 60 + cal.get(Calendar.SECOND)
}

/**
 * Is this item's campaign within its own daypart right now? Handles windows
 * that cross midnight (e.g. 22:00–02:00), same as any real daypart booking.
 */
private fun isEligibleNow(item: CachedPlaylistItem, nowSeconds: Int): Boolean {
    val start = item.dailyStartSeconds
    val end = item.dailyEndSeconds
    return if (start <= end) {
        nowSeconds in start until end
    } else {
        nowSeconds >= start || nowSeconds < end
    }
}

/**
 * Picks the next item in rotation after `lastPlayedOrder`, wrapping back to
 * the start of the (already playOrder-sorted) eligible pool once the end is
 * reached. If nothing has played yet, starts from the first eligible item.
 */
private fun pickNext(eligible: List<CachedPlaylistItem>, lastPlayedOrder: Int?): CachedPlaylistItem {
    if (lastPlayedOrder == null) return eligible.first()
    return eligible.firstOrNull { it.playOrder > lastPlayedOrder } ?: eligible.first()
}

/**
 * ROTATION LOOP — matches how real DOOH billboards actually work, not a
 * discrete-clock-time booking system. Every campaign active right now
 * (correct date, AND "now" falls within its own daily_start/end daypart)
 * forms an "eligible pool," ordered by play_order. The screen continuously
 * cycles through that pool: A, B, C, A, B, C... never going idle as long as
 * at least one campaign is eligible. This is the standard "spot rotation"
 * model — an advertiser buys ONE POSITION in the loop (or several, for more
 * frequent plays), not an exact clock-time appearance.
 *
 * Two different things drive two different decisions, kept deliberately
 * separate:
 *  - WHEN to advance within the loop: driven by each item's own playback
 *    actually finishing (video ends naturally / image's duration elapses) —
 *    NOT the clock. As soon as one item finishes, the next eligible item in
 *    play_order starts immediately — gapless, like a real rotation reel.
 *  - WHAT'S ELIGIBLE to be in the loop at all: driven by the clock, checked
 *    every second. If the currently-playing item's daypart ends mid-play
 *    (or its campaign otherwise drops out of the active list), it's cut
 *    immediately and logged as an interruption — a campaign must never be
 *    shown outside the window it was actually booked/billed for. If the
 *    pool becomes empty (no campaign eligible at all right now — e.g. 3am
 *    with nothing booked for that hour), the screen honestly goes black;
 *    no filler-content system exists yet.
 */
@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun PlayerScreen(items: List<CachedPlaylistItem>, context: Context, audioEnabled: Boolean) {
    if (items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("No active content scheduled", color = Color.DarkGray)
        }
        return
    }

    var currentItem by remember(items) { mutableStateOf<CachedPlaylistItem?>(null) }
    // True once we've already logged an outcome (completed or interrupted)
    // for the current item, so we never double-log the same play.
    var completionLogged by remember(items) { mutableStateOf(false) }
    // play_order of whichever item we most recently finished/cut away from —
    // used to pick up rotation from the right place, not always restart at
    // the top of the pool.
    var lastPlayedOrder by remember(items) { mutableStateOf<Int?>(null) }

    // Advances immediately to the next eligible item in rotation. Called
    // both by the 1s eligibility ticker (when the current item drops out of
    // its daypart) and by the slide composables themselves (when playback
    // actually finishes or errors) — the second path is what makes the loop
    // gapless instead of waiting for the next ticker cycle.
    fun advance(outgoing: CachedPlaylistItem?, outgoingCompleted: Boolean) {
        if (outgoing != null && !completionLogged) {
            SyncService.logPlayback(context, outgoing, completed = outgoingCompleted)
        }
        val now = currentSecondsOfDay()
        val eligible = items.filter { isEligibleNow(it, now) }.sortedBy { it.playOrder }
        currentItem = if (eligible.isEmpty()) null else pickNext(eligible, outgoing?.playOrder ?: lastPlayedOrder)
        if (outgoing != null) lastPlayedOrder = outgoing.playOrder
        completionLogged = false
    }

    // Eligibility ticker — only responsible for detecting when the pool
    // composition changes underneath the currently-playing item (daypart
    // boundary crossed, or the item is no longer in the active list at
    // all). Does NOT drive normal advancement; that happens instantly via
    // advance() when a slide finishes on its own.
    LaunchedEffect(items) {
        while (isActive) {
            val now = currentSecondsOfDay()
            val eligible = items.filter { isEligibleNow(it, now) }.sortedBy { it.playOrder }
            val stillEligible = currentItem != null && eligible.any { it.timeSlotId == currentItem?.timeSlotId }

            if (currentItem == null && eligible.isNotEmpty()) {
                // Nothing playing yet (first run, or pool was empty) and
                // something just became eligible — start the loop.
                advance(outgoing = null, outgoingCompleted = false)
            } else if (currentItem != null && !stillEligible) {
                // The item that's currently on screen just dropped out of
                // its daypart (or off the active list) mid-play — cut away
                // immediately and log it honestly as interrupted.
                advance(outgoing = currentItem, outgoingCompleted = false)
            }
            delay(1000)
        }
    }

    val item = currentItem

    // Dissolve between whatever was showing and what's showing now — the
    // same crossfade real billboard controllers use, instead of a hard cut.
    // Crossfade keeps BOTH the outgoing and incoming content composed and
    // rendered simultaneously for TRANSITION_DURATION_MS, animating their
    // opacity, then disposes the outgoing one once the fade completes. This
    // covers every transition uniformly: black -> first ad, ad -> ad, and
    // ad -> black when the eligible pool empties out.
    Crossfade(
        targetState = item,
        animationSpec = tween(durationMillis = TRANSITION_DURATION_MS),
        label = "playlist-item-crossfade"
    ) { crossfadeItem ->
        if (crossfadeItem == null) {
            // No campaign is eligible right now — honest black screen. No
            // filler-content system exists, so this is a genuine gap, not a bug.
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        } else {
            val isImage = crossfadeItem.localPath
                ?.substringAfterLast('.', "")
                ?.lowercase() in IMAGE_EXTENSIONS

            val onComplete: () -> Unit = {
                completionLogged = true
                advance(outgoing = crossfadeItem, outgoingCompleted = true)
            }
            val onError: () -> Unit = {
                completionLogged = true
                // Distinct from onComplete: this item never actually played
                // successfully, so it's logged as an honest failure rather than
                // a completed play. Rotation still advances immediately so a
                // single broken item can't stall the whole loop.
                advance(outgoing = crossfadeItem, outgoingCompleted = false)
            }

            if (isImage) {
                ImageSlide(crossfadeItem, onComplete)
            } else {
                VideoSlide(crossfadeItem, onComplete, onError, audioEnabled)
            }
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

    // Show the image for its own duration, then signal completion so the
    // parent immediately advances to the next item in rotation.
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
private fun VideoSlide(item: CachedPlaylistItem, onComplete: () -> Unit, onError: () -> Unit, audioEnabled: Boolean) {
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
            // Muted unless this billboard's venue explicitly opted in.
            // Outdoor/highway installs should always stay muted (driver
            // distraction/regulatory concerns, no real speaker hardware
            // anyway); indoor/retail can opt in per-billboard. See
            // Billboard.audio_enabled on the backend.
            volume = if (audioEnabled) 1f else 0f
            // Play once and stop — the coordinator advances rotation the
            // instant this finishes, so looping the same clip would just
            // mean waiting forever for a switch that already happens
            // immediately on natural completion.
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        onComplete()
                    }
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    // Corrupt file, unsupported codec, etc. Without this, a
                    // failing video just sits in an error state forever with
                    // no signal anywhere that the slot didn't actually play.
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
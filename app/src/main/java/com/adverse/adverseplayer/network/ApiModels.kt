package com.adverse.adverseplayer.network

import android.annotation.SuppressLint
import kotlinx.serialization.Serializable

// POST /players/register/
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RegisterRequest(val device_uid: String)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RegisterResponse(
    val device_uid: String,
    val pairing_code: String,
    val status: String,
    val is_paired: Boolean
)

// GET /players/pairing-status/?device_uid=...
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PairedBillboardDto(
    val id: String,
    val name: String,
    val location_name: String? = null,
    val resolution: String? = null,          // e.g. "1920x1080" — set HDMI output to match
    val operating_hours_start: String? = null,
    val operating_hours_end: String? = null,
    // Whether THIS billboard's venue wants audio. Defaults false (muted) —
    // correct for outdoor/highway installs. See PlayerScreen for how this
    // gets applied to actual video playback volume.
    val audio_enabled: Boolean = false
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PairingStatusResponse(
    val is_paired: Boolean,
    val status: String,
    val auth_token: String? = null,        // present exactly once, on first true
    // Previously declared as flat fields on this response (resolution,
    // operating_hours_start/end) which never matched the server's actual
    // shape — it nests these under "billboard", so those fields silently
    // stayed null forever with no error. Fixed to match reality.
    val billboard: PairedBillboardDto? = null
)

// POST /players/heartbeat/  (all fields optional per the server serializer)
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class HeartbeatRequest(
    val firmware_version: String? = null,
    val free_storage_mb: Long? = null,
    val current_media_id: String? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class HeartbeatResponse(
    val status: String,
    val device_uid: String,
    val last_seen_at: String? = null,
    val device_status: String? = null,
    val firmware_version: String? = null,
    val billboard: String? = null,
    // Refreshed every ~30s heartbeat — lets a venue's audio setting change
    // take effect live without needing the box to be re-paired.
    val audio_enabled: Boolean = false
)

// GET /players/players/schedule/
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TimeSlotDto(
    val id: String,
    val date: String,
    val play_order: Int,
    val duration_seconds: Int,
    // The campaign's daypart — the device filters to "eligible right now"
    // against its own clock using this, same as scheduled_time used to
    // drive selection, since rotation membership is daypart-based now
    // rather than an exact clock-time booking. Format: "HH:MM:SS".
    val daily_start_time: String,
    val daily_end_time: String,
    val campaign_name: String,
    val media_title: String,
    val media_url: String,
    val advertiser: String,
    val is_active: Boolean,
    val content_hash: String
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ScheduleResponse(
    val device_uid: String,
    val billboard: String,
    val schedule: List<TimeSlotDto>,
    val fetched_at: String
)

// POST /players/players/playback/bulk/  — max 500 logs per call
@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PlaybackLogEntry(
    val media_id: String,
    val started_at: String,      // ISO-8601, also the idempotency key alongside media_id
    val duration_seconds: Int,
    val completed: Boolean,
    val time_slot_id: String? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class PlaybackBulkRequest(val logs: List<PlaybackLogEntry>)
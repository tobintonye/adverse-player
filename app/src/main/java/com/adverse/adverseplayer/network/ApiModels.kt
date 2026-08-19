package com.adverse.adverseplayer.network

import kotlinx.serialization.Serializable

// POST /players/register/
@Serializable
data class RegisterRequest(val device_uid: String)

@Serializable
data class RegisterResponse(
    val device_uid: String,
    val pairing_code: String,
    val status: String,
    val is_paired: Boolean
)

// GET /players/pairing-status/?device_uid=...
@Serializable
data class PairingStatusResponse(
    val is_paired: Boolean,
    val status: String,
    val auth_token: String? = null,        // present exactly once, on first true
    val resolution: String? = null,          // e.g. "1920x1080" — set HDMI output to match
    val operating_hours_start: String? = null,
    val operating_hours_end: String? = null
)

// POST /players/heartbeat/  (all fields optional per the server serializer)
@Serializable
data class HeartbeatRequest(
    val firmware_version: String? = null,
    val free_storage_mb: Long? = null,
    val current_media_id: String? = null
)

// GET /players/players/schedule/
@Serializable
data class TimeSlotDto(
    val id: String,
    val date: String,
    val play_order: Int,
    val duration_seconds: Int,
    val campaign_name: String,
    val media_title: String,
    val media_url: String,
    val advertiser: String,
    val is_active: Boolean,
    val content_hash: String
)

@Serializable
data class ScheduleResponse(
    val device_uid: String,
    val billboard: String,
    val schedule: List<TimeSlotDto>,
    val fetched_at: String
)

// POST /players/players/playback/bulk/  — max 500 logs per call
@Serializable
data class PlaybackLogEntry(
    val media_id: String,
    val started_at: String,      // ISO-8601, also the idempotency key alongside media_id
    val duration_seconds: Int,
    val completed: Boolean,
    val time_slot_id: String? = null
)

@Serializable
data class PlaybackBulkRequest(val logs: List<PlaybackLogEntry>)

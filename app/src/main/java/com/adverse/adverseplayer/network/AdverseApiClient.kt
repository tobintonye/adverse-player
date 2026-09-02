package com.adverse.adverseplayer.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Points at your Django deployment. Swap this for your real domain before
 * building a release APK — during development this can be an ngrok URL.
 */
object ApiConfig {
    const val BASE_URL = "https://branchlike-nonaltruistically-toya.ngrok-free.dev/adverse-api/players/"
}

sealed class ScheduleResult {
    data class Updated(val schedule: ScheduleResponse, val lastModified: String?) : ScheduleResult()
    object NotModified : ScheduleResult()
    data class Error(val message: String) : ScheduleResult()
}

class AdverseApiClient {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false // we handle non-2xx (incl. 304, 401) ourselves
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 20_000
            connectTimeoutMillis = 10_000
        }
        install(Logging) { level = LogLevel.INFO }
    }

    /** Idempotent — safe to call on every boot before a token exists. */
    suspend fun register(deviceUid: String): Result<RegisterResponse> = runCatching {
        val response: HttpResponse = client.post("${ApiConfig.BASE_URL}register/") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(deviceUid))
        }
        if (!response.status.isSuccess()) error("register failed: ${response.status}")
        response.body()
    }

    /** Poll every ~10s while unpaired. Server hands back the token exactly once. */
    suspend fun pairingStatus(deviceUid: String): Result<PairingStatusResponse> = runCatching {
        val response = client.get("${ApiConfig.BASE_URL}pairing-status/") {
            parameter("device_uid", deviceUid)
        }
        if (!response.status.isSuccess()) error("pairing-status failed: ${response.status}")
        response.body()
    }

    suspend fun heartbeat(token: String, body: HeartbeatRequest): Result<HeartbeatResponse> = runCatching {
        val response = client.post("${ApiConfig.BASE_URL}heartbeat/") {
            deviceAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        when {
            response.status.value == 401 -> throw UnauthorizedException()
            !response.status.isSuccess() -> error("heartbeat failed: ${response.status}")
            else -> response.body()
        }
    }

    /** Sends If-Modified-Since when we have one; returns NotModified on a 304. */
    suspend fun schedule(token: String, lastModified: String?): ScheduleResult = try {
        val response = client.get("${ApiConfig.BASE_URL}schedule/") {
            deviceAuth(token)
            lastModified?.let { header("If-Modified-Since", it) }
        }
        when {
            response.status.value == 304 -> ScheduleResult.NotModified
            response.status.value == 401 -> throw UnauthorizedException()
            response.status.isSuccess() -> ScheduleResult.Updated(
                schedule = response.body(),
                lastModified = response.headers["Last-Modified"]
            )
            else -> ScheduleResult.Error("schedule fetch failed: ${response.status}")
        }
    } catch (e: UnauthorizedException) {
        throw e
    } catch (e: Exception) {
        ScheduleResult.Error(e.message ?: "unknown network error")
    }

    /** Max 500 entries per call per the server's bulk endpoint. */
    suspend fun playbackBulk(token: String, logs: List<PlaybackLogEntry>): Result<Unit> = runCatching {
        require(logs.size <= 500) { "playbackBulk accepts at most 500 logs per call" }
        val response = client.post("${ApiConfig.BASE_URL}playback/bulk/") {
            deviceAuth(token)
            contentType(ContentType.Application.Json)
            setBody(PlaybackBulkRequest(logs))
        }
        when {
            response.status.value == 401 -> throw UnauthorizedException()
            !response.status.isSuccess() -> error("playback bulk upload failed: ${response.status}")
            else -> Unit
        }
    }

    private fun HttpRequestBuilder.deviceAuth(token: String) {
        header("Authorization", "DeviceToken $token")
    }
}

/** Thrown when the server says the token is invalid/disabled — caller should
 *  clear local state and fall back to the unpaired/registration flow. */
class UnauthorizedException : Exception("Device token rejected by server (401)")
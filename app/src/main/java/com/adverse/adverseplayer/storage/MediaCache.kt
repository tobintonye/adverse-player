package com.adverse.adverseplayer.storage

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads media into app-private storage and verifies it before handing
 * the local path back. ExoPlayer only ever reads from here — never from a
 * network URL directly — so a dead connection never blanks the screen.
 */
class MediaCache(private val context: Context) {

    private val client = HttpClient(OkHttp)

    private val adsDir: File
        get() = File(context.filesDir, "ads").apply { if (!exists()) mkdirs() }

    /** File name is content-hash-based so a re-uploaded replacement (same
     *  slot, new creative) never collides with, or gets confused for, the
     *  file it's replacing. */
    fun localFileFor(contentHash: String, mediaUrl: String): File {
        val ext = mediaUrl.substringAfterLast('.', "mp4").take(5)
        return File(adsDir, "$contentHash.$ext")
    }

    suspend fun download(contentHash: String, mediaUrl: String): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val target = localFileFor(contentHash, mediaUrl)
            val tmp = File(adsDir, "${target.name}.part")

            val response = client.get(mediaUrl)
            tmp.outputStream().use { out ->
                response.bodyAsChannel().copyTo(out)
            }

            // Basic integrity check: a real ad is never a 0-byte file. A
            // stricter setup would compare against a server-provided
            // content_hash/size; this is the minimum viable guard.
            if (tmp.length() <= 0L) {
                tmp.delete()
                error("downloaded file for $contentHash was empty")
            }

            if (target.exists()) target.delete()
            tmp.renameTo(target)
            target
        }.onSuccess { file ->
            Log.d("MediaCache", "download succeeded: hash=$contentHash size=${file.length()} bytes -> ${file.absolutePath}")
        }.onFailure { e ->
            // This used to fail silently — a failed download just left
            // localPath null and the item quietly never appeared in
            // getPlayable(), with zero trace of why. Log it loudly instead.
            Log.e("MediaCache", "download FAILED for hash=$contentHash url=$mediaUrl", e)
        }
    }

    /** Deletes any cached file not referenced by the current playlist. */
    fun pruneUnused(keepFileNames: Set<String>) {
        adsDir.listFiles()?.forEach { file ->
            if (file.name !in keepFileNames) file.delete()
        }
    }
}
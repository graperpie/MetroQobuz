/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.youtube

import com.metrolist.innertube.YouTube
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object YouTubeAudioProvider {
    const val STREAM_MARKER_QUERY = "_metrolist_youtube"

    private const val TAG = "YouTubeAudioProvider"
    private const val STREAM_MARKER_VALUE = "youtube"
    private const val MIN_TARGET_KBPS = 128
    private const val MAX_TARGET_KBPS = 160
    private const val MAX_TARGET_BPS = MAX_TARGET_KBPS * 1000
    private const val DEFAULT_STREAM_CACHE_MS = 5 * 60 * 1000L
    private const val WATCH_URL_PREFIX = "https://www.youtube.com/watch?v="
    private const val ORIGIN_YOUTUBE = "https://www.youtube.com"
    private const val REFERER_YOUTUBE = "https://www.youtube.com/"

    private val streamCache = ConcurrentHashMap<String, Resolved>()
    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .proxyAuthenticator { _, response ->
            YouTube.proxyAuth?.let { auth ->
                response.request
                    .newBuilder()
                    .header("Proxy-Authorization", auth)
                    .build()
            } ?: response.request
        }
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val extractorDownloader = YouTubeExtractorDownloader(httpClient)

    @Volatile
    private var extractorInitialized = false

    data class Resolved(
        val mediaUri: String,
        val videoId: String,
        val itag: Int,
        val mimeType: String,
        val codecs: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long?,
        val loudnessDb: Double?,
        val perceptualLoudnessDb: Double?,
        val expiresAtMs: Long,
    )

    class YouTubeAudioResolutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

    suspend fun resolve(videoId: String): Resolved {
        val now = System.currentTimeMillis()
        streamCache[videoId]
            ?.takeIf { it.expiresAtMs > now + 30_000L }
            ?.let { return it }

        ensureExtractorInitialized()

        val streamInfo = runCatching {
            StreamInfo.getInfo("$WATCH_URL_PREFIX$videoId")
        }.getOrElse { error ->
            throw YouTubeAudioResolutionException(
                "YouTube extractor could not read stream info for $videoId: ${error.readableMessage()}",
                error,
            )
        }

        val stream = selectAudioStream(streamInfo.audioStreams)
            ?: throw YouTubeAudioResolutionException(
                "No direct YouTube audio stream found for $videoId at ${MIN_TARGET_KBPS}-${MAX_TARGET_KBPS} kbps",
                streamInfo.errors.firstOrNull(),
            )

        val streamUrl = stream.content.takeIf { stream.isUrl && it.isNotBlank() }
            ?: throw YouTubeAudioResolutionException("Selected YouTube audio stream is not a direct URL")

        val streamStatus = validateStreamUrl(streamUrl)
        if (streamStatus !in 200..299) {
            throw YouTubeAudioResolutionException(
                "Selected YouTube audio stream returned HTTP ${streamStatus ?: "unknown"}",
            )
        }

        val expiresAtMs = resolveExpiryMs(streamUrl, now)
        if (expiresAtMs <= now + 45_000L) {
            throw YouTubeAudioResolutionException("Selected YouTube audio stream expires too soon")
        }

        return Resolved(
            mediaUri = addStreamMarker(streamUrl),
            videoId = videoId,
            itag = stream.itag.takeIf { it > 0 } ?: stream.id.toIntOrNull() ?: -1,
            mimeType = stream.mimeType,
            codecs = stream.codecString,
            bitrate = stream.safeDisplayBitrate,
            sampleRate = stream.itagItem?.sampleRate?.takeIf { it > 0 },
            contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 },
            loudnessDb = null,
            perceptualLoudnessDb = null,
            expiresAtMs = expiresAtMs,
        ).also { resolved ->
            Timber.tag(TAG).i(
                "Resolved YouTube fallback stream for $videoId: " +
                    "itag=${resolved.itag}, mime=${resolved.mimeType}, bitrate=${resolved.bitrate}",
            )
            streamCache[videoId] = resolved
        }
    }

    fun invalidate(videoId: String) {
        streamCache.remove(videoId)
    }

    fun userAgentFor(clientKey: String?): String = YouTubeExtractorDownloader.USER_AGENT

    fun addYouTubePlaybackHeaders(
        builder: Request.Builder,
        clientKey: String?,
        hasRangeHeader: Boolean,
    ): Request.Builder {
        return addYouTubeHeaders(builder, clientKey)
            .apply {
                if (!hasRangeHeader) {
                    header("Range", "bytes=0-")
                }
            }
    }

    fun addYouTubeHeaders(
        builder: Request.Builder,
        clientKey: String?,
    ): Request.Builder {
        return builder
            .header("User-Agent", YouTubeExtractorDownloader.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Origin", ORIGIN_YOUTUBE)
            .header("Referer", REFERER_YOUTUBE)
            .apply {
                YouTube.cookie?.takeIf { it.isNotBlank() }?.let { header("Cookie", it) }
            }
    }

    @Synchronized
    private fun ensureExtractorInitialized() {
        if (extractorInitialized) return

        NewPipe.init(
            extractorDownloader,
            Localization("en", "US"),
            ContentCountry("US"),
        )
        extractorInitialized = true
    }

    private fun selectAudioStream(streams: List<AudioStream>): AudioStream? {
        val directStreams = streams
            .asSequence()
            .filter { it.isUrl }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .filter { it.content.isNotBlank() }
            .filter { it.averageKbps in 1..MAX_TARGET_KBPS || it.fallbackBitrateBps in 1..MAX_TARGET_BPS }
            .toList()

        val inTarget = directStreams.filter { it.averageKbps in MIN_TARGET_KBPS..MAX_TARGET_KBPS }
        val underCap = directStreams.filter { it.effectiveKbps in 1..MAX_TARGET_KBPS }

        return (inTarget.ifEmpty { underCap })
            .minWithOrNull(
                compareBy<AudioStream> { it.formatPreference }
                    .thenBy { if (it.effectiveKbps >= MIN_TARGET_KBPS) 0 else 1 }
                    .thenByDescending { it.effectiveKbps },
            )
    }

    private fun validateStreamUrl(url: String): Int? {
        return try {
            val request = addYouTubePlaybackHeaders(
                Request.Builder().url(url),
                STREAM_MARKER_VALUE,
                hasRangeHeader = false,
            ).build()
            httpClient.newCall(request).execute().use { response ->
                response.code
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "YouTube fallback stream validation failed")
            null
        }
    }

    private fun addStreamMarker(url: String): String =
        url.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter(STREAM_MARKER_QUERY, STREAM_MARKER_VALUE)
            ?.build()
            ?.toString()
            ?: url

    private fun resolveExpiryMs(url: String, now: Long): Long {
        val urlExpiry = url.toHttpUrlOrNull()
            ?.queryParameter("expire")
            ?.toLongOrNull()
            ?.times(1000L)
            ?.minus(30_000L)
        return listOfNotNull(urlExpiry, now + DEFAULT_STREAM_CACHE_MS)
            .minOrNull()
            ?.coerceAtLeast(now + 30_000L)
            ?: (now + DEFAULT_STREAM_CACHE_MS)
    }

    private val AudioStream.averageKbps: Int
        get() = averageBitrate.takeIf { it > 0 }
            ?: (bitrate.takeIf { it > 0 }?.div(1000))
            ?: 0

    private val AudioStream.effectiveKbps: Int
        get() = averageKbps.takeIf { it > 0 }
            ?: fallbackBitrateBps.div(1000)

    private val AudioStream.fallbackBitrateBps: Int
        get() = bitrate.takeIf { it > 0 } ?: 0

    private val AudioStream.safeDisplayBitrate: Int
        get() = (averageKbps.takeIf { it > 0 }?.times(1000) ?: fallbackBitrateBps)
            .takeIf { it > 0 }
            ?.coerceAtMost(MAX_TARGET_BPS)
            ?: 0

    private val AudioStream.formatPreference: Int
        get() = when (format) {
            MediaFormat.M4A -> 0
            MediaFormat.WEBMA_OPUS -> 1
            MediaFormat.WEBMA -> 2
            else -> 3
        }

    private val AudioStream.mimeType: String
        get() = format?.mimeType ?: when {
            codecString.startsWith("mp4a", ignoreCase = true) -> "audio/mp4"
            codecString.equals("opus", ignoreCase = true) -> "audio/webm"
            else -> "audio/mp4"
        }

    private val AudioStream.codecString: String
        get() = codec
            ?.takeIf { it.isNotBlank() }
            ?: when (format) {
                MediaFormat.M4A -> "mp4a.40.2"
                MediaFormat.WEBMA_OPUS -> "opus"
                MediaFormat.WEBMA -> "opus"
                else -> ""
            }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    private class YouTubeExtractorDownloader(
        private val client: OkHttpClient,
    ) : Downloader() {
        override fun execute(request: ExtractorRequest): ExtractorResponse {
            val requestBody = request.dataToSend()?.toRequestBody()
            val builder = Request.Builder()
                .url(request.url())
                .method(request.httpMethod(), requestBody)
                .header("User-Agent", USER_AGENT)

            if (request.url().isYouTubeHost()) {
                YouTube.cookie?.takeIf { it.isNotBlank() }?.let { builder.header("Cookie", it) }
            }

            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { value -> builder.addHeader(name, value) }
            }

            try {
                client.newCall(builder.build()).execute().use { response ->
                    if (response.code == 429) {
                        throw ReCaptchaException("reCaptcha Challenge requested", request.url())
                    }

                    return ExtractorResponse(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        response.body?.string().orEmpty(),
                        response.request.url.toString(),
                    )
                }
            } catch (e: ReCaptchaException) {
                throw e
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                throw IOException("YouTube extractor request failed", e)
            }
        }

        private fun String.isYouTubeHost(): Boolean {
            return toHttpUrlOrNull()?.host?.let { host ->
                host == "youtube.com" ||
                    host.endsWith(".youtube.com") ||
                    host == "googlevideo.com" ||
                    host.endsWith(".googlevideo.com")
            } ?: false
        }

        companion object {
            const val USER_AGENT =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        }
    }
}

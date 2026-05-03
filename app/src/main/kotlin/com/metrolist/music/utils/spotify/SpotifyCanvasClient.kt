/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils.spotify

import com.metrolist.music.models.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

data class SpotifyCanvasMedia(
    val url: String,
    val headers: Map<String, String>,
)

fun normalizeSpotifyCookieInput(input: String): String? {
    val trimmedInput =
        input
            .trim()
            .removePrefix("Cookie:")
            .removePrefix("cookie:")
            .trim()
            .trim(';')
    if (trimmedInput.isBlank()) return null

    val scopedCookie =
        trimmedInput
            .split(';', '\n', '\r')
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
            ?.trim('"')
            ?.takeIf { it.isNotBlank() }

    val rawCookie =
        if (scopedCookie != null) {
            scopedCookie
        } else if (
            trimmedInput.contains(';') ||
            trimmedInput.contains('\n') ||
            trimmedInput.contains('\r') ||
            trimmedInput.contains(" ")
        ) {
            null
        } else {
            trimmedInput
                .substringAfter("sp_dc=", trimmedInput)
                .trim()
                .trim('"')
                .takeIf { it.isNotBlank() }
        }

    return rawCookie?.let { "sp_dc=$it" }
}

fun isSpotifyCookieConfigured(value: String): Boolean = normalizeSpotifyCookieInput(value) != null

object SpotifyCanvasClient {
    private const val SEARCH_TRACKS_HASH =
        "5307479c18ff24aa1bd70691fdb0e77734bede8cce3bd7d43b6ff7314f52a6b8"
    private const val CANVAS_HASH =
        "1b1e1915481c99f4349af88268c6b49a2b601cf0db7bca8749b5dd75088486fc"

    private const val DEVICE_AUTH_URL = "https://accounts.spotify.com/oauth2/device/authorize"
    private const val DEVICE_TOKEN_URL = "https://accounts.spotify.com/api/token"
    private const val DEVICE_RESOLVE_URL = "https://accounts.spotify.com/pair/api/resolve"
    private const val DEVICE_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
    private const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
    private const val DEVICE_SCOPE =
        "app-remote-control,playlist-modify,playlist-modify-private,playlist-modify-public," +
            "playlist-read,playlist-read-collaborative,playlist-read-private,streaming," +
            "transfer-auth-session,ugc-image-upload,user-follow-modify,user-follow-read," +
            "user-library-modify,user-library-read,user-modify,user-modify-playback-state," +
            "user-modify-private,user-personalized,user-read-birthdate," +
            "user-read-currently-playing,user-read-email,user-read-play-history," +
            "user-read-playback-position,user-read-playback-state,user-read-private," +
            "user-read-recently-played,user-top-read"

    private const val WEB_REFERER = "https://open.spotify.com/"
    private const val WEB_ORIGIN = "https://open.spotify.com"
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private const val DESKTOP_USER_AGENT = "Spotify/126600447 Win32_x86_64/0 (PC laptop)"
    private const val CACHE_TTL_MS = 6 * 60 * 60 * 1000L
    private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    private val NEXT_DATA_REGEX =
        Regex(
            """<script id="__NEXT_DATA__" type="application/json"[^>]*>(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

    private val json = Json { ignoreUnknownKeys = true }
    private val client =
        OkHttpClient
            .Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    private val tokenMutex = Mutex()
    private var token: String? = null
    private var tokenExpiryMs = 0L
    private var activeCookie: String? = null

    private data class CachedString(
        val value: String,
        val cachedAt: Long,
    )

    private data class Expectation(
        val title: String,
        val artists: List<String>,
        val album: String?,
        val durationMs: Long?,
    ) {
        val key: String
            get() =
                listOf(
                    normalizeForMatch(title),
                    artists.joinToString("|") { normalizeForMatch(it) },
                    normalizeForMatch(album.orEmpty()),
                    durationMs?.toString().orEmpty(),
                ).joinToString("::")
    }

    private val trackUriCache = ConcurrentHashMap<String, CachedString>()
    private val canvasUrlCache = ConcurrentHashMap<String, CachedString>()

    suspend fun resolveBackground(
        mediaMetadata: MediaMetadata,
        cookie: String,
    ): SpotifyCanvasMedia? {
        if (mediaMetadata.isEpisode || mediaMetadata.isVideoSong) return null
        val normalizedCookie = normalizeSpotifyCookieInput(cookie) ?: return null
        val expectation = buildExpectation(mediaMetadata) ?: return null
        val trackUri = resolveTrackUri(expectation, normalizedCookie) ?: return null
        val canvasUrl = resolveCanvas(trackUri, normalizedCookie) ?: return null
        return SpotifyCanvasMedia(
            url = canvasUrl,
            headers = buildCanvasHeaders(trackUri),
        )
    }

    private suspend fun resolveTrackUri(
        expectation: Expectation,
        cookie: String,
    ): String? {
        val now = System.currentTimeMillis()
        trackUriCache[expectation.key]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value.ifBlank { null } }

        val candidates =
            buildQueries(expectation)
                .flatMap { searchTracks(it, cookie) }
                .distinctBy { it.uri }

        val bestMatch =
            candidates
                .map { it to scoreTrack(it, expectation) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second >= 55 }
                ?.first
                ?.uri

        trackUriCache[expectation.key] = CachedString(bestMatch.orEmpty(), now)
        return bestMatch
    }

    private suspend fun resolveCanvas(
        trackUri: String,
        cookie: String,
    ): String? {
        val now = System.currentTimeMillis()
        canvasUrlCache[trackUri]
            ?.takeIf { now - it.cachedAt < CACHE_TTL_MS }
            ?.let { return it.value.ifBlank { null } }

        val response =
            postGraphQl<CanvasResponse>(
                operation = "canvas",
                hash = CANVAS_HASH,
                variables =
                    buildJsonObject {
                        put("uri", trackUri)
                    },
                cookie = cookie,
            )

        val canvasUrl =
            response.data
                ?.trackUnion
                ?.canvas
                ?.takeIf { it.type.orEmpty().startsWith("VIDEO") }
                ?.url
                ?.takeIf { it.isNotBlank() }

        canvasUrlCache[trackUri] = CachedString(canvasUrl.orEmpty(), now)
        return canvasUrl
    }

    private suspend fun searchTracks(
        query: String,
        cookie: String,
    ): List<SearchTrack> {
        if (query.isBlank()) return emptyList()

        val response =
            postGraphQl<SearchTracksResponse>(
                operation = "searchTracks",
                hash = SEARCH_TRACKS_HASH,
                variables =
                    buildJsonObject {
                        put("searchTerm", query)
                        put("offset", 0)
                        put("limit", 10)
                        put("numberOfTopResults", 5)
                        put("includeAudiobooks", false)
                        put("includePreReleases", true)
                    },
                cookie = cookie,
            )

        return response.data
            ?.searchV2
            ?.tracksV2
            ?.items
            .orEmpty()
            .mapNotNull { it.item?.data?.takeIf { track -> !track.uri.isNullOrBlank() } }
    }

    private suspend inline fun <reified T> postGraphQl(
        operation: String,
        hash: String,
        variables: JsonObject,
        cookie: String,
    ): T =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url("https://api-partner.spotify.com/pathfinder/v2/query")
                    .post(
                        buildJsonObject {
                            put("operationName", operation)
                            put("variables", variables)
                            putJsonObject("extensions") {
                                putJsonObject("persistedQuery") {
                                    put("version", 1)
                                    put("sha256Hash", hash)
                                }
                            }
                        }.toString().toRequestBody(JSON_MEDIA_TYPE),
                    ).header("User-Agent", WEB_USER_AGENT)
                    .header("Accept", "application/json")
                    .header("App-Platform", "WebPlayer")
                    .header("Referer", WEB_REFERER)
                    .header("Origin", WEB_ORIGIN)
                    .header("Cookie", cookie)
                    .header("Authorization", "Bearer ${ensureToken(cookie)}")
                    .build()

            client.newCall(request).execute().use { response ->
                json.decodeFromString<T>(response.requireBody(operation))
            }
        }

    private suspend fun ensureToken(cookie: String): String =
        tokenMutex.withLock {
            if (activeCookie != cookie) {
                activeCookie = cookie
                token = null
                tokenExpiryMs = 0L
            }

            token
                ?.takeIf { System.currentTimeMillis() < tokenExpiryMs }
                ?.let { return it }

            val freshToken =
                createDesktopAccessToken(
                    extractSpDc(cookie) ?: error("Spotify cookie must include sp_dc"),
                )

            token = freshToken.accessToken
            tokenExpiryMs = System.currentTimeMillis() + freshToken.expiresIn * 1000L - 60_000L
            freshToken.accessToken
        }

    private suspend fun createDesktopAccessToken(spDc: String): DesktopAccessToken {
        val authorization = initiateDesktopDeviceAuthorization()
        val flowClient = newDesktopDeviceFlowClient(spDc)
        val verification =
            parseDesktopVerificationPage(
                flowClient = flowClient,
                url = authorization.verificationUriComplete,
            )
        submitDesktopUserCode(
            flowClient = flowClient,
            userCode = authorization.userCode,
            flowContext = verification.flowContext,
            csrfToken = verification.csrfToken,
            refererUrl = authorization.verificationUriComplete,
        )
        return exchangeDesktopDeviceCode(authorization.deviceCode)
    }

    private suspend fun initiateDesktopDeviceAuthorization(): DesktopDeviceAuthorization =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(DEVICE_AUTH_URL)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .post(
                        FormBody
                            .Builder()
                            .add("client_id", DEVICE_CLIENT_ID)
                            .add("scope", DEVICE_SCOPE)
                            .build(),
                    ).build()

            client.newCall(request).execute().use { response ->
                json.decodeFromString<DesktopDeviceAuthorizationResponse>(
                    response.requireBody("device authorization"),
                ).toAuth()
            }
        }

    private suspend fun parseDesktopVerificationPage(
        flowClient: OkHttpClient,
        url: String,
    ): DesktopVerificationContext =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .get()
                    .build()

            flowClient.newCall(request).execute().use { response ->
                val flowContext =
                    response.request.url
                        .queryParameter("flow_ctx")
                        ?.substringBefore(':')
                        ?: error("Spotify verification page missing flow_ctx")

                DesktopVerificationContext(
                    flowContext = flowContext,
                    csrfToken = extractDesktopCsrfToken(response.requireBody("verification page")),
                )
            }
        }

    private suspend fun submitDesktopUserCode(
        flowClient: OkHttpClient,
        userCode: String,
        flowContext: String,
        csrfToken: String,
        refererUrl: String,
    ) = withContext(Dispatchers.IO) {
        val url =
            DEVICE_RESOLVE_URL
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("flow_ctx", "$flowContext:${System.currentTimeMillis() / 1000}")
                .build()

        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("x-csrf-token", csrfToken)
                .header("referer", refererUrl)
                .header("origin", "https://accounts.spotify.com")
                .post(
                    json
                        .encodeToString(DesktopResolveRequest(userCode))
                        .toRequestBody(JSON_MEDIA_TYPE),
                ).build()

        flowClient.newCall(request).execute().use { response ->
            val result =
                json.decodeFromString<DesktopResolveResponse>(
                    response.requireBody("device confirmation"),
                ).result
            check(result == "ok") { "Spotify device confirmation failed: $result" }
        }
    }

    private suspend fun exchangeDesktopDeviceCode(deviceCode: String): DesktopAccessToken =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(DEVICE_TOKEN_URL)
                    .header("User-Agent", DESKTOP_USER_AGENT)
                    .post(
                        FormBody
                            .Builder()
                            .add("client_id", DEVICE_CLIENT_ID)
                            .add("device_code", deviceCode)
                            .add("grant_type", DEVICE_GRANT_TYPE)
                            .build(),
                    ).build()

            client.newCall(request).execute().use { response ->
                json.decodeFromString<DesktopTokenResponse>(
                    response.requireBody("token exchange"),
                ).toToken()
            }
        }

    private fun newDesktopDeviceFlowClient(spDc: String): OkHttpClient {
        val cookieStore =
            DesktopCookieStore().apply {
                seed(
                    Cookie
                        .Builder()
                        .name("sp_dc")
                        .value(spDc)
                        .domain("accounts.spotify.com")
                        .path("/")
                        .secure()
                        .httpOnly()
                        .build(),
                )
                seed(
                    Cookie
                        .Builder()
                        .name("sp_dc")
                        .value(spDc)
                        .domain("spotify.com")
                        .path("/")
                        .secure()
                        .httpOnly()
                        .build(),
                )
            }

        return client
            .newBuilder()
            .addNetworkInterceptor { chain ->
                val originalRequest = chain.request()
                val mergedCookie =
                    mergeCookieHeader(
                        originalRequest.header("Cookie"),
                        cookieStore.loadForRequest(originalRequest.url),
                    )

                val request =
                    originalRequest
                        .newBuilder()
                        .header("User-Agent", DESKTOP_USER_AGENT)
                        .apply {
                            if (mergedCookie.isNotBlank()) {
                                header("Cookie", mergedCookie)
                            }
                            if (originalRequest.header("Referer").isNullOrBlank()) {
                                header("Referer", WEB_REFERER)
                            }
                        }.build()

                val response = chain.proceed(request)
                response
                    .headers("Set-Cookie")
                    .forEach { rawCookie ->
                        Cookie.parse(request.url, rawCookie)?.let(cookieStore::store)
                    }
                response
            }.build()
    }

    private fun extractDesktopCsrfToken(html: String): String {
        val nextData =
            NEXT_DATA_REGEX
                .find(html)
                ?.groupValues
                ?.get(1)
                ?: error("Spotify verification page missing __NEXT_DATA__")

        return json.decodeFromString<DesktopNextData>(nextData).props?.initialToken
            ?: error("Spotify verification page missing CSRF token")
    }

    private fun buildExpectation(mediaMetadata: MediaMetadata): Expectation? {
        val title = mediaMetadata.title.trim().takeIf { it.isNotBlank() } ?: return null
        val artists =
            mediaMetadata.artists
                .map { it.name.trim() }
                .filter { it.isNotBlank() }
        val album = mediaMetadata.album?.title?.trim()?.takeIf { it.isNotBlank() }
        val durationMs = mediaMetadata.duration.takeIf { it > 0 }?.times(1000L)
        return if (artists.isEmpty() && album == null) null else Expectation(title, artists, album, durationMs)
    }

    private fun buildQueries(expectation: Expectation): List<String> {
        val title = sanitize(expectation.title)
        val artistQuery = expectation.artists.joinToString(" ").ifBlank { null }
        val albumQuery = expectation.album?.let(::sanitize)

        return listOfNotNull(
            listOfNotNull(artistQuery, title, albumQuery).joinToString(" ").trim().takeIf { it.isNotBlank() },
            listOfNotNull(artistQuery, title).joinToString(" ").trim().takeIf { it.isNotBlank() },
            title.takeIf { it.isNotBlank() },
        ).distinct()
    }

    private fun scoreTrack(
        candidate: SearchTrack,
        expectation: Expectation,
    ): Int {
        val title = normalizeForMatch(candidate.name.orEmpty())
        val expectedTitle = normalizeForMatch(expectation.title)
        val artists =
            candidate.artists
                ?.items
                .orEmpty()
                .mapNotNull { it.profile?.name }
                .map(::normalizeForMatch)
        val expectedArtists = expectation.artists.map(::normalizeForMatch)
        val album = normalizeForMatch(candidate.albumOfTrack?.name.orEmpty())
        val expectedAlbum = normalizeForMatch(expectation.album.orEmpty())
        val durationPenalty =
            expectation.durationMs?.let { expectedDuration ->
                abs((candidate.duration?.totalMilliseconds ?: expectedDuration) - expectedDuration)
            } ?: 0L

        val titleScore =
            when {
                title == expectedTitle -> 40
                title.contains(expectedTitle) -> 30
                expectedTitle.contains(title) -> 24
                overlap(title, expectedTitle) >= 0.75 -> 18
                overlap(title, expectedTitle) >= 0.5 -> 10
                else -> 0
            }

        val artistScore =
            expectedArtists.sumOf { expectedArtist ->
                when {
                    artists.any { it == expectedArtist } -> 18
                    artists.any { it.contains(expectedArtist) || expectedArtist.contains(it) } -> 10
                    else -> 0
                }
            }

        val albumScore =
            when {
                expectedAlbum.isBlank() -> 0
                album == expectedAlbum -> 10
                album.contains(expectedAlbum) || expectedAlbum.contains(album) -> 6
                else -> 0
            }

        val durationScore =
            when {
                durationPenalty <= 2_000L -> 20
                durationPenalty <= 5_000L -> 14
                durationPenalty <= 10_000L -> 8
                durationPenalty >= 30_000L -> -12
                else -> 0
            }

        return titleScore + artistScore + albumScore + durationScore - disfavoredPenalty(
            candidateTitle = candidate.name.orEmpty(),
            expectedTitle = expectation.title,
        )
    }

    private fun buildCanvasHeaders(trackUri: String): Map<String, String> =
        mapOf(
            "User-Agent" to WEB_USER_AGENT,
            "Referer" to "https://open.spotify.com/track/${trackUri.substringAfterLast(':')}",
            "Origin" to WEB_ORIGIN,
            "Accept" to "*/*",
        )

    private fun extractSpDc(cookie: String): String? = normalizeSpotifyCookieInput(cookie)?.substringAfter("sp_dc=")

    private fun mergeCookieHeader(
        original: String?,
        scoped: List<Cookie>,
    ): String {
        val cookies = linkedMapOf<String, String>()
        original
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.contains('=') }
            ?.forEach { cookie ->
                cookies[cookie.substringBefore('=')] = cookie.substringAfter('=')
            }
        scoped.forEach { cookie ->
            cookies[cookie.name] = cookie.value
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun Response.requireBody(step: String): String {
        val text = body?.string().orEmpty()
        check(isSuccessful) { "$step failed: ${text.ifBlank { "$code $message" }}" }
        return text.ifBlank { error("$step returned an empty body") }
    }

    private class DesktopCookieStore {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        fun seed(cookie: Cookie) {
            store(cookie)
        }

        @Synchronized
        fun store(cookie: Cookie) {
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) {
                cookies += cookie
            }
        }

        @Synchronized
        fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { cookie ->
                cookie.expiresAt > System.currentTimeMillis() && cookie.matches(url)
            }
    }

    @Serializable
    private data class DesktopDeviceAuthorizationResponse(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri_complete") val verificationUriComplete: String,
    ) {
        fun toAuth() = DesktopDeviceAuthorization(deviceCode, userCode, verificationUriComplete)
    }

    @Serializable
    private data class DesktopResolveRequest(
        val code: String,
    )

    @Serializable
    private data class DesktopResolveResponse(
        val result: String,
    )

    @Serializable
    private data class DesktopTokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Int,
    ) {
        fun toToken() = DesktopAccessToken(accessToken, expiresIn)
    }

    @Serializable
    private data class DesktopNextData(
        val props: DesktopNextDataProps? = null,
    )

    @Serializable
    private data class DesktopNextDataProps(
        val initialToken: String? = null,
    )

    private data class DesktopDeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUriComplete: String,
    )

    private data class DesktopVerificationContext(
        val flowContext: String,
        val csrfToken: String,
    )

    private data class DesktopAccessToken(
        val accessToken: String,
        val expiresIn: Int,
    )

    @Serializable
    private data class SearchTracksResponse(
        val data: SearchTracksData? = null,
    )

    @Serializable
    private data class SearchTracksData(
        @SerialName("searchV2") val searchV2: SearchV2? = null,
    )

    @Serializable
    private data class SearchV2(
        @SerialName("tracksV2") val tracksV2: SearchTracksContainer? = null,
    )

    @Serializable
    private data class SearchTracksContainer(
        val items: List<SearchTrackWrapperWrapper> = emptyList(),
    )

    @Serializable
    private data class SearchTrackWrapperWrapper(
        val item: SearchTrackWrapper? = null,
    )

    @Serializable
    private data class SearchTrackWrapper(
        val data: SearchTrack? = null,
    )

    @Serializable
    private data class SearchTrack(
        val uri: String? = null,
        val name: String? = null,
        val duration: SearchDuration? = null,
        val artists: SearchArtists? = null,
        val albumOfTrack: SearchAlbum? = null,
    )

    @Serializable
    private data class SearchDuration(
        val totalMilliseconds: Long? = null,
    )

    @Serializable
    private data class SearchArtists(
        val items: List<SearchArtist> = emptyList(),
    )

    @Serializable
    private data class SearchArtist(
        val profile: SearchArtistProfile? = null,
    )

    @Serializable
    private data class SearchArtistProfile(
        val name: String? = null,
    )

    @Serializable
    private data class SearchAlbum(
        val name: String? = null,
    )

    @Serializable
    private data class CanvasResponse(
        val data: CanvasResponseData? = null,
    )

    @Serializable
    private data class CanvasResponseData(
        val trackUnion: CanvasTrackUnion? = null,
    )

    @Serializable
    private data class CanvasTrackUnion(
        val canvas: CanvasData? = null,
    )

    @Serializable
    private data class CanvasData(
        val type: String? = null,
        val url: String? = null,
    )
}

private fun normalizeForMatch(value: String): String =
    value
        .lowercase()
        .replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        .replace(Regex("\\b(feat\\.?|ft\\.?|with)\\b"), " ")
        .replace(Regex("\\b(official|video|music|lyric|lyrics|audio|hd|4k|remastered|remaster)\\b"), " ")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun sanitize(value: String): String =
    value
        .replace(
            Regex("\\(feat\\.?[^)]*\\)|\\[feat\\.?[^]]*\\]", RegexOption.IGNORE_CASE),
            " ",
        ).replace(Regex("\\b(feat\\.?|ft\\.?)\\b", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun overlap(
    left: String,
    right: String,
): Double {
    val leftTokens = left.split(" ").filter { it.isNotBlank() }.toSet()
    val rightTokens = right.split(" ").filter { it.isNotBlank() }.toSet()
    if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
    return leftTokens.intersect(rightTokens).size.toDouble() / maxOf(leftTokens.size, rightTokens.size).toDouble()
}

private fun disfavoredPenalty(
    candidateTitle: String,
    expectedTitle: String,
): Int {
    val candidate = candidateTitle.lowercase()
    val expected = expectedTitle.lowercase()
    val penalties =
        mapOf(
            "live" to 12,
            "karaoke" to 18,
            "cover" to 14,
            "tribute" to 14,
            "instrumental" to 10,
            "sped up" to 18,
            "speed up" to 18,
            "slowed" to 18,
            "reverb" to 10,
        )
    return penalties.entries.sumOf { (token, penalty) ->
        if (candidate.contains(token) && !expected.contains(token)) {
            penalty
        } else {
            0
        }
    }
}

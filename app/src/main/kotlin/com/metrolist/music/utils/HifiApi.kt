package com.metrolist.music.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber
import java.io.StringReader

object HifiApi {
    private const val TAG = "HifiApi"
    private const val DEFAULT_BASE_URL = "https://api.monochrome.tf"
    
    // We need a specific JSON instance that ignores unknown keys just in case.
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient()

    /**
     * Searches for a track by title and author via HiFi API and returns its Tidal ID.
     */
    fun searchTrack(title: String, author: String, baseUrl: String = DEFAULT_BASE_URL): String? {
        Timber.tag(TAG).d("Searching track on HiFi API: $title - $author")
        try {
            val url = "$baseUrl/search/".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("s", "$title $author")
                ?.addQueryParameter("limit", "1")
                ?.build() ?: return null

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).e("Search failed with code: ${response.code}")
                    return null
                }
                
                val bodyString = response.body?.string() ?: return null
                Timber.tag(TAG).d("Search response length: ${bodyString.length}")
                
                val jsonObject = json.parseToJsonElement(bodyString).jsonObject
                val data = jsonObject["data"]?.jsonObject ?: return null
                val items = data["items"]?.jsonArray ?: return null
                
                if (items.isEmpty()) {
                    Timber.tag(TAG).d("No track found.")
                    return null
                }
                
                val firstItem = items[0].jsonObject
                val id = firstItem["id"]?.jsonPrimitive?.intOrNull?.toString()
                
                Timber.tag(TAG).d("Found track ID: $id")
                return id
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error searching track")
            return null
        }
    }

    /**
     * Retrieves the stream URL given a Tidal track ID and desired quality.
     * Valid qualities: HI_RES_LOSSLESS, LOSSLESS, HIGH, LOW
     */
    fun getStreamUrl(trackId: String, quality: String, baseUrl: String = DEFAULT_BASE_URL): String? {
        Timber.tag(TAG).d("Getting stream URL for track ID $trackId with quality $quality")
        try {
            val url = "$baseUrl/track/".toHttpUrlOrNull()?.newBuilder()
                ?.addQueryParameter("id", trackId)
                ?.addQueryParameter("quality", quality)
                ?.build() ?: return null

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.tag(TAG).e("Get stream URL failed with code: ${response.code}")
                    return null
                }
                
                val bodyString = response.body?.string() ?: return null
                
                val jsonObject = json.parseToJsonElement(bodyString).jsonObject
                val data = jsonObject["data"]?.jsonObject ?: return null
                val manifestHash = data["manifestHash"]?.jsonPrimitive?.content ?: return null
                val manifestMimeType = data["manifestMimeType"]?.jsonPrimitive?.content ?: return null
                
                // Usually the manifest endpoints from Tidal provide a manifest string
                // HiFi API returns it base64 encoded.
                val base64Manifest = data["manifest"]?.jsonPrimitive?.content ?: return null
                
                // Let's decode it.
                val decodedManifest = String(android.util.Base64.decode(base64Manifest, android.util.Base64.DEFAULT))
                
                if (manifestMimeType == "application/vnd.tidal.bts") {
                    // It's JSON format
                    val manifestJson = json.parseToJsonElement(decodedManifest).jsonObject
                    val urlsArray = manifestJson["urls"]?.jsonArray
                    if (urlsArray != null && urlsArray.isNotEmpty()) {
                        val streamUrl = urlsArray[0].jsonPrimitive.content
                        Timber.tag(TAG).d("Extracted JSON stream URL")
                        return streamUrl
                    }
                } else if (manifestMimeType == "application/dash+xml") {
                    val streamUrl = parseDashManifest(decodedManifest)
                    if (streamUrl != null) {
                        Timber.tag(TAG).d("Extracted DASH stream URL")
                        return streamUrl
                    }
                    
                    Timber.tag(TAG).w("Failed to parse DASH manifest. Quality $quality might not play.")
                    // Fall back to LOSSLESS if we have a DASH manifest for HI_RES and parsing failed
                    if (quality == "HI_RES_LOSSLESS") {
                         Timber.tag(TAG).d("Falling back to LOSSLESS since HI_RES_LOSSLESS DASH parsing failed")
                         return getStreamUrl(trackId, "LOSSLESS", baseUrl)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error getting stream URL")
        }
        
        // Final fallback: if HI_RES_LOSSLESS failed for any reason (404, parsing error, etc), try LOSSLESS
        if (quality == "HI_RES_LOSSLESS") {
            Timber.tag(TAG).d("Final fallback: attempting LOSSLESS after HI_RES_LOSSLESS failure")
            return getStreamUrl(trackId, "LOSSLESS", baseUrl)
        }
        
        return null
    }

    /**
     * Parses a DASH manifest to extract the stream URL from the BaseURL tag.
     */
    private fun parseDashManifest(manifest: String): String? {
        try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(manifest))
            
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val name = parser.name
                    if (name == "BaseURL") {
                        if (parser.next() == XmlPullParser.TEXT) {
                            return parser.text
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error parsing DASH manifest")
        }
        return null
    }
}

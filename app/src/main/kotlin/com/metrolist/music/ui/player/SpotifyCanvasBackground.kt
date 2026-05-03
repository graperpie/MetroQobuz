/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.player

import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.spotify.SpotifyCanvasClient
import com.metrolist.music.utils.spotify.SpotifyCanvasMedia
import com.metrolist.music.utils.spotify.normalizeSpotifyCookieInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@Composable
fun rememberSpotifyCanvasMedia(
    mediaMetadata: MediaMetadata?,
    enabled: Boolean,
    cookie: String,
    shouldLoad: Boolean,
): SpotifyCanvasMedia? {
    val normalizedCookie = normalizeSpotifyCookieInput(cookie)
    val canvasMedia by produceState<SpotifyCanvasMedia?>(
        initialValue = null,
        mediaMetadata,
        enabled,
        normalizedCookie,
        shouldLoad,
    ) {
        if (!enabled || !shouldLoad || mediaMetadata == null || normalizedCookie == null) {
            value = null
            return@produceState
        }

        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    SpotifyCanvasClient.resolveBackground(mediaMetadata, normalizedCookie)
                }.getOrNull()
            }
    }

    return canvasMedia
}

@Composable
fun SpotifyCanvasVideoBackground(
    media: SpotifyCanvasMedia,
    shouldPlay: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val textureView =
        remember {
            TextureView(context).apply {
                isOpaque = false
                isClickable = false
                isFocusable = false
            }
        }
    val okHttpClient =
        remember {
            OkHttpClient
                .Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    val player =
        remember(media.url, media.headers) {
            val dataSourceFactory =
                OkHttpDataSource
                    .Factory(okHttpClient)
                    .setDefaultRequestProperties(media.headers)

            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .apply {
                    setAudioAttributes(AudioAttributes.DEFAULT, false)
                    repeatMode = Player.REPEAT_MODE_ONE
                    volume = 0f
                    playWhenReady = shouldPlay
                    setVideoTextureView(textureView)
                    setMediaItem(MediaItem.fromUri(media.url))
                    prepare()
                }
        }

    DisposableEffect(player, lifecycleOwner, textureView) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> if (shouldPlay) player.play()
                    Lifecycle.Event.ON_PAUSE -> player.pause()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.clearVideoTextureView(textureView)
            player.release()
        }
    }

    LaunchedEffect(player, shouldPlay) {
        player.playWhenReady = shouldPlay
        if (shouldPlay) {
            player.play()
        } else {
            player.pause()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { textureView },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.16f)),
        )
    }
}

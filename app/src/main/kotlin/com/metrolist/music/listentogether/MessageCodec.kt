/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import timber.log.Timber

/**
 * Codec for encoding and decoding messages using Protocol Buffers
 * STUBBED for build stabilization. Listen Together functionality will be disabled.
 */
class MessageCodec(
    var compressionEnabled: Boolean = false
) {
    companion object {
        private const val TAG = "MessageCodec"
    }
    
    /**
     * Encode a message using Protocol Buffers
     */
    fun encode(msgType: String, payload: Any?): ByteArray {
        Timber.tag(TAG).w("encode stubbed")
        return ByteArray(0)
    }
    
    /**
     * Decode a protobuf message
     */
    fun decode(data: ByteArray): Pair<String, ByteArray> {
        Timber.tag(TAG).w("decode stubbed")
        return Pair("", ByteArray(0))
    }
    
    /**
     * Decode protobuf payload to Kotlin objects
     */
    fun decodePayload(msgType: String, payloadBytes: ByteArray): Any? {
        Timber.tag(TAG).w("decodePayload stubbed")
        return null
    }

    fun encodePayload(payload: Any): ByteArray {
        Timber.tag(TAG).w("encodePayload stubbed")
        return ByteArray(0)
    }
}

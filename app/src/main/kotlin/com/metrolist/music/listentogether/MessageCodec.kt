/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.listentogether

import timber.log.Timber

/**
 * MessageCodec stubbed to allow build without Protobuf generation.
 */
class MessageCodec(val useCompression: Boolean = true) {
    fun encode(msgType: String, payload: Any?): ByteArray {
        Timber.tag("MessageCodec").w("MessageCodec.encode stubbed")
        return ByteArray(0)
    }

    fun decode(bytes: ByteArray): Pair<String, ByteArray> {
        Timber.tag("MessageCodec").w("MessageCodec.decode stubbed")
        return "" to ByteArray(0)
    }

    fun decodePayload(msgType: String, bytes: ByteArray): Any? {
        Timber.tag("MessageCodec").w("MessageCodec.decodePayload stubbed")
        return null
    }
}

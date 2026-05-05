package com.metrolist.music.listentogether
// this better work
import com.google.protobuf.ByteString
import com.metrolist.music.listentogether.proto.*
import com.metrolist.music.listentogether.proto.Listentogether as Proto
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * encodes and decodes network messages
 */
class MessageCodec(private val useCompression: Boolean = true) {
    companion object {
        private const val TAG = "MessageCodec"
        private const val COMPRESSION_THRESHOLD = 512
    }

    /**
     * encode a message type and its payload into a byte array.
     */
    fun encode(
        type: String,
        payload: Any?,
    ): ByteArray {
        try {
            val payloadBytes = if (payload != null) encodePayload(type, payload) else ByteArray(0)

            val shouldCompress = useCompression && payloadBytes.size > COMPRESSION_THRESHOLD
            val finalPayload = if (shouldCompress) compress(payloadBytes) else payloadBytes

            val env = envelope {
                this.type = type
                this.payload = ByteString.copyFrom(finalPayload)
                this.compressed = shouldCompress
            }

            return env.toByteArray()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error encoding message of type $type")
            throw e
        }
    }

    /**
     * decode a byte array into a message type and its raw payload bytes.
     */
    fun decode(data: ByteArray): Pair<String, ByteArray> {
        try {
            val env = Proto.Envelope.parseFrom(data)
            val payloadBytes =
                if (env.compressed) {
                    decompress(env.payload.toByteArray())
                } else {
                    env.payload.toByteArray()
                }
            return env.type to payloadBytes
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error decoding envelope")
            throw e
        }
    }

    /**
     * decode raw payload bytes into a specific message object based on type
     */
    fun decodePayload(
        type: String,
        data: ByteArray,
    ): Any? {
        if (data.isEmpty()) return null

        return try {
            when (type) {
                // Server -> Client messages
                MessageTypes.ROOM_CREATED -> Proto.RoomCreatedPayload.parseFrom(data).toDomain()
                MessageTypes.JOIN_REQUEST -> Proto.JoinRequestPayload.parseFrom(data).toDomain()
                MessageTypes.JOIN_APPROVED -> Proto.JoinApprovedPayload.parseFrom(data).toDomain()
                MessageTypes.JOIN_REJECTED -> Proto.JoinRejectedPayload.parseFrom(data).toDomain()
                MessageTypes.USER_JOINED -> Proto.UserJoinedPayload.parseFrom(data).toDomain()
                MessageTypes.USER_LEFT -> Proto.UserLeftPayload.parseFrom(data).toDomain()
                MessageTypes.SYNC_PLAYBACK -> Proto.PlaybackActionPayload.parseFrom(data).toDomain()
                MessageTypes.BUFFER_WAIT -> Proto.BufferWaitPayload.parseFrom(data).toDomain()
                MessageTypes.BUFFER_COMPLETE -> Proto.BufferCompletePayload.parseFrom(data).toDomain()
                MessageTypes.ERROR -> Proto.ErrorPayload.parseFrom(data).toDomain()
                MessageTypes.HOST_CHANGED -> Proto.HostChangedPayload.parseFrom(data).toDomain()
                MessageTypes.KICKED -> Proto.KickedPayload.parseFrom(data).toDomain()
                MessageTypes.SYNC_STATE -> Proto.SyncStatePayload.parseFrom(data).toDomain()
                MessageTypes.RECONNECTED -> Proto.ReconnectedPayload.parseFrom(data).toDomain()
                MessageTypes.USER_RECONNECTED -> Proto.UserReconnectedPayload.parseFrom(data).toDomain()
                MessageTypes.USER_DISCONNECTED -> Proto.UserDisconnectedPayload.parseFrom(data).toDomain()
                MessageTypes.SUGGESTION_RECEIVED -> Proto.SuggestionReceivedPayload.parseFrom(data).toDomain()
                MessageTypes.SUGGESTION_APPROVED -> Proto.SuggestionApprovedPayload.parseFrom(data).toDomain()
                MessageTypes.SUGGESTION_REJECTED -> Proto.SuggestionRejectedPayload.parseFrom(data).toDomain()
                MessageTypes.PONG -> null
                else -> {
                    Timber.tag(TAG).w("Unknown message type for decoding: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error decoding payload for type $type")
            null
        }
    }

    private fun encodePayload(
        type: String,
        payload: Any,
    ): ByteArray {
        return when (type) {
            // Client -> Server messages
            MessageTypes.CREATE_ROOM -> (payload as CreateRoomPayload).toProto().toByteArray()
            MessageTypes.JOIN_ROOM -> (payload as JoinRoomPayload).toProto().toByteArray()
            MessageTypes.APPROVE_JOIN -> (payload as ApproveJoinPayload).toProto().toByteArray()
            MessageTypes.REJECT_JOIN -> (payload as RejectJoinPayload).toProto().toByteArray()
            MessageTypes.PLAYBACK_ACTION -> (payload as PlaybackActionPayload).toProto().toByteArray()
            MessageTypes.BUFFER_READY -> (payload as BufferReadyPayload).toProto().toByteArray()
            MessageTypes.KICK_USER -> (payload as KickUserPayload).toProto().toByteArray()
            MessageTypes.TRANSFER_HOST -> (payload as TransferHostPayload).toProto().toByteArray()
            MessageTypes.SUGGEST_TRACK -> (payload as SuggestTrackPayload).toProto().toByteArray()
            MessageTypes.APPROVE_SUGGESTION -> (payload as ApproveSuggestionPayload).toProto().toByteArray()
            MessageTypes.REJECT_SUGGESTION -> (payload as RejectSuggestionPayload).toProto().toByteArray()
            MessageTypes.RECONNECT -> (payload as ReconnectPayload).toProto().toByteArray()
            MessageTypes.PING, MessageTypes.REQUEST_SYNC, MessageTypes.LEAVE_ROOM -> ByteArray(0)
            else -> {
                Timber.tag(TAG).w("Unknown message type for encoding: $type")
                ByteArray(0)
            }
        }
    }

    // GZIP Helpers

    private fun compress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream(data.size)
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun decompress(data: ByteArray): ByteArray {
        val bis = ByteArrayInputStream(data)
        return GZIPInputStream(bis).use { it.readBytes() }
    }

    // Mapping: Domain -> Proto

    private fun TrackInfo.toProto(): Proto.TrackInfo =
        trackInfo {
            id = this@toProto.id
            title = this@toProto.title
            artist = this@toProto.artist
            this@toProto.album?.let { album = it }
            duration = this@toProto.duration
            this@toProto.thumbnail?.let { thumbnail = it }
            this@toProto.suggestedBy?.let { suggestedBy = it }
        }

    private fun UserInfo.toProto(): Proto.UserInfo =
        userInfo {
            userId = this@toProto.userId
            username = this@toProto.username
            isHost = this@toProto.isHost
            isConnected = this@toProto.isConnected
        }

    private fun RoomState.toProto(): Proto.RoomState =
        roomState {
            roomCode = this@toProto.roomCode
            hostId = this@toProto.hostId
            users.addAll(this@toProto.users.map { it.toProto() })
            this@toProto.currentTrack?.let { currentTrack = it.toProto() }
            isPlaying = this@toProto.isPlaying
            position = this@toProto.position
            lastUpdate = this@toProto.lastUpdate
            volume = this@toProto.volume
            queue.addAll(this@toProto.queue.map { it.toProto() })
        }

    private fun CreateRoomPayload.toProto(): Proto.CreateRoomPayload =
        createRoomPayload {
            username = this@toProto.username
        }

    private fun JoinRoomPayload.toProto(): Proto.JoinRoomPayload =
        joinRoomPayload {
            roomCode = this@toProto.roomCode
            username = this@toProto.username
        }

    private fun ApproveJoinPayload.toProto(): Proto.ApproveJoinPayload =
        approveJoinPayload {
            userId = this@toProto.userId
        }

    private fun RejectJoinPayload.toProto(): Proto.RejectJoinPayload =
        rejectJoinPayload {
            userId = this@toProto.userId
            this@toProto.reason?.let { reason = it }
        }

    private fun PlaybackActionPayload.toProto(): Proto.PlaybackActionPayload =
        playbackActionPayload {
            action = this@toProto.action
            this@toProto.trackId?.let { trackId = it }
            this@toProto.position?.let { position = it }
            this@toProto.trackInfo?.let { trackInfo = it.toProto() }
            this@toProto.insertNext?.let { insertNext = it }
            this@toProto.queue?.let { queue.addAll(it.map { t -> t.toProto() }) }
            this@toProto.queueTitle?.let { queueTitle = it }
            this@toProto.volume?.let { volume = it }
            this@toProto.serverTime?.let { serverTime = it }
        }

    private fun BufferReadyPayload.toProto(): Proto.BufferReadyPayload =
        bufferReadyPayload {
            trackId = this@toProto.trackId
        }

    private fun KickUserPayload.toProto(): Proto.KickUserPayload =
        kickUserPayload {
            userId = this@toProto.userId
            this@toProto.reason?.let { reason = it }
        }

    private fun TransferHostPayload.toProto(): Proto.TransferHostPayload =
        transferHostPayload {
            newHostId = this@toProto.newHostId
        }

    private fun SuggestTrackPayload.toProto(): Proto.SuggestTrackPayload =
        suggestTrackPayload {
            trackInfo = this@toProto.trackInfo.toProto()
        }

    private fun ApproveSuggestionPayload.toProto(): Proto.ApproveSuggestionPayload =
        approveSuggestionPayload {
            suggestionId = this@toProto.suggestionId
        }

    private fun RejectSuggestionPayload.toProto(): Proto.RejectSuggestionPayload =
        rejectSuggestionPayload {
            suggestionId = this@toProto.suggestionId
            this@toProto.reason?.let { reason = it }
        }

    private fun ReconnectPayload.toProto(): Proto.ReconnectPayload =
        reconnectPayload {
            sessionToken = this@toProto.sessionToken
        }

    // Mapping: Proto -> Domain

    private fun Proto.TrackInfo.toDomain(): TrackInfo =
        TrackInfo(
            id = id,
            title = title,
            artist = artist,
            album = if (hasAlbum()) album else null,
            duration = duration,
            thumbnail = if (hasThumbnail()) thumbnail else null,
            suggestedBy = if (hasSuggestedBy()) suggestedBy else null,
        )

    private fun Proto.UserInfo.toDomain(): UserInfo =
        UserInfo(
            userId = userId,
            username = username,
            isHost = isHost,
            isConnected = isConnected,
        )

    private fun Proto.RoomState.toDomain(): RoomState =
        RoomState(
            roomCode = roomCode,
            hostId = hostId,
            users = usersList.map { it.toDomain() },
            currentTrack = if (hasCurrentTrack()) currentTrack.toDomain() else null,
            isPlaying = isPlaying,
            position = position,
            lastUpdate = lastUpdate,
            volume = volume,
            queue = queueList.map { it.toDomain() },
        )

    private fun Proto.RoomCreatedPayload.toDomain(): RoomCreatedPayload =
        RoomCreatedPayload(
            roomCode = roomCode,
            userId = userId,
            sessionToken = sessionToken,
        )

    private fun Proto.JoinRequestPayload.toDomain(): JoinRequestPayload =
        JoinRequestPayload(
            userId = userId,
            username = username,
        )

    private fun Proto.JoinApprovedPayload.toDomain(): JoinApprovedPayload =
        JoinApprovedPayload(
            roomCode = roomCode,
            userId = userId,
            sessionToken = sessionToken,
            state = state.toDomain(),
        )

    private fun Proto.JoinRejectedPayload.toDomain(): JoinRejectedPayload =
        JoinRejectedPayload(
            reason = reason,
        )

    private fun Proto.UserJoinedPayload.toDomain(): UserJoinedPayload =
        UserJoinedPayload(
            userId = userId,
            username = username,
        )

    private fun Proto.UserLeftPayload.toDomain(): UserLeftPayload =
        UserLeftPayload(
            userId = userId,
            username = username,
        )

    private fun Proto.PlaybackActionPayload.toDomain(): PlaybackActionPayload =
        PlaybackActionPayload(
            action = action,
            trackId = if (hasTrackId()) trackId else null,
            position = if (hasPosition()) position else null,
            trackInfo = if (hasTrackInfo()) trackInfo.toDomain() else null,
            insertNext = if (hasInsertNext()) insertNext else null,
            queue = if (queueCount > 0) queueList.map { it.toDomain() } else null,
            queueTitle = if (hasQueueTitle()) queueTitle else null,
            volume = if (hasVolume()) volume else null,
            serverTime = if (hasServerTime()) serverTime else null,
        )

    private fun Proto.BufferWaitPayload.toDomain(): BufferWaitPayload =
        BufferWaitPayload(
            trackId = trackId,
            waitingFor = waitingForList,
        )

    private fun Proto.BufferCompletePayload.toDomain(): BufferCompletePayload =
        BufferCompletePayload(
            trackId = trackId,
        )

    private fun Proto.ErrorPayload.toDomain(): ErrorPayload =
        ErrorPayload(
            code = code,
            message = message,
        )

    private fun Proto.HostChangedPayload.toDomain(): HostChangedPayload =
        HostChangedPayload(
            newHostId = newHostId,
            newHostName = newHostName,
        )

    private fun Proto.KickedPayload.toDomain(): KickedPayload =
        KickedPayload(
            reason = reason,
        )

    private fun Proto.SyncStatePayload.toDomain(): SyncStatePayload =
        SyncStatePayload(
            currentTrack = if (hasCurrentTrack()) currentTrack.toDomain() else null,
            isPlaying = isPlaying,
            position = position,
            lastUpdate = lastUpdate,
            queue = if (queueCount > 0) queueList.map { it.toDomain() } else null,
            volume = if (hasVolume()) volume else null,
        )

    private fun Proto.ReconnectedPayload.toDomain(): ReconnectedPayload =
        ReconnectedPayload(
            roomCode = roomCode,
            userId = userId,
            state = state.toDomain(),
            isHost = isHost,
        )

    private fun Proto.UserReconnectedPayload.toDomain(): UserReconnectedPayload =
        UserReconnectedPayload(
            userId = userId,
            username = username,
        )

    private fun Proto.UserDisconnectedPayload.toDomain(): UserDisconnectedPayload =
        UserDisconnectedPayload(
            userId = userId,
            username = username,
        )

    private fun Proto.SuggestionReceivedPayload.toDomain(): SuggestionReceivedPayload =
        SuggestionReceivedPayload(
            suggestionId = suggestionId,
            fromUserId = fromUserId,
            fromUsername = fromUsername,
            trackInfo = trackInfo.toDomain(),
        )

    private fun Proto.SuggestionApprovedPayload.toDomain(): SuggestionApprovedPayload =
        SuggestionApprovedPayload(
            suggestionId = suggestionId,
            trackInfo = trackInfo.toDomain(),
        )

    private fun Proto.SuggestionRejectedPayload.toDomain(): SuggestionRejectedPayload =
        SuggestionRejectedPayload(
            suggestionId = suggestionId,
            reason = if (hasReason()) reason else null,
        )
}

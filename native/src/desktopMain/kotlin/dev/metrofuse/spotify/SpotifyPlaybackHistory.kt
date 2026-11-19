package dev.metrofuse.spotify

import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.Track
import dev.metrofuse.api.UpcomingQueuePlayer
import dev.metrofuse.spotify.legacy.MediaMetadata
import dev.metrofuse.spotify.legacy.SpotifyCanvasClient
import dev.metrofuse.spotify.legacy.SpotifyListeningHistoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

internal interface HistoryActions {
    fun start(
        track: MediaMetadata,
        context: String?,
    )

    fun state(
        playing: Boolean,
        track: MediaMetadata,
        context: String?,
    )

    fun seek(
        position: Long,
        playing: Boolean,
        track: MediaMetadata,
        context: String?,
    )

    fun stop()
}

/** Feed only real occurrence transitions and seeks to the legacy history protocol. */
internal class PlaybackHistoryObserver(
    private val actions: HistoryActions,
) {
    private var previous: PlaybackState? = null
    private var observedAt = 0L
    private var activeOccurrence: String? = null
    private var activeTrackId: String? = null
    private var activeQueueIndex: Int? = null

    fun accept(
        value: PlaybackState,
        enabled: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        val last = previous
        val elapsed = (now - observedAt).coerceAtLeast(0)
        previous = value
        observedAt = now
        if (!enabled || value.track.id.isBlank()) {
            stop()
            return
        }
        val occurrence = value.playbackInstanceId
        val track = value.track.legacy()
        val context = value.track.extras["spotify.contextUri"]
        val trackChanged = activeTrackId != value.track.id
        val occurrenceChanged = occurrence != null && activeOccurrence != null && occurrence != activeOccurrence
        val indexChanged = occurrence == null && activeQueueIndex != null &&
            value.currentIndex != null && activeQueueIndex != value.currentIndex
        if (trackChanged || occurrenceChanged || indexChanged) {
            if (!value.playing) {
                stop()
                return
            }
            // Some host controllers omit occurrence IDs. Wait for the playback clock to
            // advance before starting a listen, and adopt a late ID on the same track.
            if (track.duration <= 0 ||
                (occurrence == null && (last == null || last.track.id != value.track.id || last.position >= value.position))
            ) {
                stop()
                return
            }
            activeTrackId = value.track.id
            activeQueueIndex = value.currentIndex
            activeOccurrence = occurrence
            actions.start(track, context)
            if (value.position > 1000) actions.seek(value.position, true, track, context)
            return
        }
        if (occurrence != null) activeOccurrence = occurrence
        if (value.currentIndex != null) activeQueueIndex = value.currentIndex
        if (last != null && last.track.id == value.track.id) {
            if (last.playing != value.playing) actions.state(value.playing, track, context)
            val expected = last.position + if (last.playing) elapsed else 0
            if (abs(value.position - expected) >= 2000) actions.seek(value.position, value.playing, track, context)
        }
    }

    private fun stop() {
        if (activeTrackId != null) actions.stop()
        activeTrackId = null
        activeOccurrence = null
        activeQueueIndex = null
    }
}

internal class SpotifyPlaybackHistory(
    private val context: PluginContext,
    private val cookie: String,
    private val currentCookie: () -> String?,
) {
    private val accountScope = CoroutineScope(context.scope.coroutineContext + SupervisorJob(context.scope.coroutineContext[Job]))
    private var protocolScope: CoroutineScope? = null
    private var manager: SpotifyListeningHistoryManager? = null
    private var observer: PlaybackHistoryObserver? = null

    init {
        accountScope.launch {
            combine(context.player.state, context.settings.values) { playback, settings ->
                Triple(playback.track.id, playback.playbackInstanceId, settings["queueSyncEnabled"]?.jsonPrimitive?.booleanOrNull == true)
            }.distinctUntilChanged().collectLatest { (_, _, enabled) ->
                if (enabled) {
                    while (currentCookie() === cookie) {
                        synchronizeQueue(context.player.state.value)
                        delay(20_000)
                    }
                }
            }
        }
        accountScope.launch {
            combine(context.player.state, context.settings.values) { playback, settings ->
                playback to (settings["historyEnabled"]?.jsonPrimitive?.booleanOrNull == true)
            }.collect { (playback, enabled) ->
                if (!enabled || currentCookie() !== cookie) {
                    val wasActive = manager != null
                    stopProtocol()
                    if (wasActive &&
                        context.settings.values.value["queueSyncEnabled"]
                            ?.jsonPrimitive
                            ?.booleanOrNull != true
                    ) {
                        SpotifyCanvasClient.stopWebPlayerHistory()
                    }
                } else {
                    if (manager == null) startProtocol()
                    observer?.accept(playback, true)
                }
            }
        }
    }

    private fun startProtocol() {
        val scope =
            CoroutineScope(accountScope.coroutineContext + SupervisorJob(accountScope.coroutineContext[Job])).also {
                protocolScope =
                    it
            }
        val history =
            SpotifyListeningHistoryManager(scope, {
                cookie.takeIf {
                    currentCookie() === cookie &&
                        context.settings.values.value["historyEnabled"]
                            ?.jsonPrimitive
                            ?.booleanOrNull == true
                }
            }, onSpotifyAutoplayQueueReady = { metadata ->
                scope.launch {
                    val value = context.player.state.value
                    if (value.track.legacy().id == metadata.id) synchronizeQueue(value)
                }
            }).also { manager = it }
        observer =
            PlaybackHistoryObserver(
                object : HistoryActions {
                    override fun start(
                        track: MediaMetadata,
                        context: String?,
                    ) = history.onSongStart(track, playbackContextUri = context, newOccurrence = true)

                    override fun state(
                        playing: Boolean,
                        track: MediaMetadata,
                        context: String?,
                    ) = history.onPlayerStateChanged(playing, track, playbackContextUri = context)

                    override fun seek(
                        position: Long,
                        playing: Boolean,
                        track: MediaMetadata,
                        context: String?,
                    ) = history.onSeek(position, playing, track, playbackContextUri = context)

                    override fun stop() = history.onSongStop()
                },
            )
    }

    private suspend fun synchronizeQueue(snapshot: PlaybackState) {
        val player = context.player as? UpcomingQueuePlayer ?: return
        if ("replaceUpcomingQueue" !in player.capabilities || !queueSyncEnabled() || snapshot.track.id.isBlank()) return
        val tracks =
            try {
                SpotifyCanvasClient.resolveWebPlayerQueue(snapshot.track.legacy(), cookie, refresh = true)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            } ?: return
        val value = context.player.state.value
        if (!queueSyncEnabled() || value.playbackInstanceId != snapshot.playbackInstanceId || value.track.id != snapshot.track.id) return
        val current = value.currentIndex ?: return
        val upcoming = tracks.map { it.track().owned() }
        if (value.queue.drop(current + 1).map(Track::id) != upcoming.map(Track::id)) player.replaceUpcomingQueue(upcoming)
    }

    private fun queueSyncEnabled(): Boolean =
        currentCookie() === cookie && context.settings.values.value["queueSyncEnabled"]
            ?.jsonPrimitive
            ?.booleanOrNull == true

    private fun stopProtocol() {
        manager?.destroy()
        manager = null
        observer = null
        protocolScope?.cancel()
        protocolScope = null
    }

    fun close() {
        stopProtocol()
        accountScope.cancel()
    }
}

internal fun Track.owned(): Track =
    copy(
        id = "metrofuse:metrofuse.spotify:$id",
        extras =
            extras + mapOf("pluginOwner" to "metrofuse.spotify", "providerTrackId" to id),
    )

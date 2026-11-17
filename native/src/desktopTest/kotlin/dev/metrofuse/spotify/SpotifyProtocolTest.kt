package dev.metrofuse.spotify

import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.Track
import dev.metrofuse.spotify.legacy.Artist
import dev.metrofuse.spotify.legacy.MediaMetadata
import dev.metrofuse.spotify.legacy.SongItem
import dev.metrofuse.spotify.legacy.SpotifyCanvasClient
import dev.metrofuse.spotify.legacy.normalizeSpotifyCookieInput
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class SpotifyProtocolTest {
    @Test fun rapidDuplicateOccurrencesHaveSeparateTransportSessionsAndPlaybackIds() = runBlocking {
        SpotifyCanvasClient.resetAccountState()
        val now = System.currentTimeMillis()
        val firstStart = SpotifyCanvasClient.nextListeningHistoryStartedAt(now)
        val secondStart = SpotifyCanvasClient.nextListeningHistoryStartedAt(now)
        assertTrue(secondStart > firstStart)
        val uri = "spotify:track:" + "1".repeat(22)
        val firstEvent = SpotifyCanvasClient.listeningHistoryVerification(uri, firstStart, 180000).getValue("message").jsonObject
        val secondEvent = SpotifyCanvasClient.listeningHistoryVerification(uri, secondStart, 180000).getValue("message").jsonObject
        assertNotEquals(firstEvent["session_id"], secondEvent["session_id"])
        assertNotEquals(firstEvent["playback_id"], secondEvent["playback_id"])
        assertEquals(firstEvent, SpotifyCanvasClient.listeningHistoryVerification(uri, firstStart, 180000).getValue("message").jsonObject)
        SpotifyCanvasClient.resetAccountState()
    }

    private val first = "1".repeat(22)
    private val second = "2".repeat(22)

    private fun track(id: String) =
        buildJsonObject {
            put("uri", "spotify:track:$id")
            put(
                "metadata",
                buildJsonObject {
                    put("name", "Fixture $id")
                    put("artist_name", "Fixture Artist")
                    put("image_url", "https://i.scdn.co/image/fixture")
                    put("duration", 180000)
                },
            )
        }

    @Test fun connectQueuePreservesOccurrencesAndRejectsAStaleCurrentTrack() =
        runBlocking {
            SpotifyCanvasClient.resetAccountState()
            val cluster =
                buildJsonObject {
                    put(
                        "player_state",
                        buildJsonObject {
                            put("track", track(first))
                            put(
                                "next_tracks",
                                buildJsonArray {
                                    add(track(second))
                                    add(track(second))
                                    add(track(first))
                                },
                            )
                        },
                    )
                }
            assertFalse(SpotifyCanvasClient.cacheSpotifyConnectClusterQueue(cluster, second))
            assertNull(SpotifyCanvasClient.cachedWebPlayerQueue(first))
            assertTrue(SpotifyCanvasClient.cacheSpotifyConnectClusterQueue(cluster, first))
            assertEquals(
                listOf(second, second, first),
                SpotifyCanvasClient.cachedWebPlayerQueue(first)!!.map { it.id.substringAfterLast(':') },
            )
            val longQueue =
                buildJsonObject {
                    put(
                        "player_state",
                        buildJsonObject {
                            put("track", track(first))
                            put("next_tracks", buildJsonArray { repeat(60) { add(track(second)) } })
                        },
                    )
                }
            assertTrue(SpotifyCanvasClient.cacheSpotifyConnectClusterQueue(longQueue, first))
            assertEquals(60, SpotifyCanvasClient.cachedWebPlayerQueue(first)!!.size)
            val malformed = buildJsonObject { put("player_state", buildJsonObject { put("track", track(first)) }) }
            assertFalse(SpotifyCanvasClient.cacheSpotifyConnectClusterQueue(malformed, first))
            assertEquals(60, SpotifyCanvasClient.cachedWebPlayerQueue(first)!!.size)
            SpotifyCanvasClient.resetAccountState()
            assertNull(SpotifyCanvasClient.cachedWebPlayerQueue(first))
        }

    @Test fun stateMachineQueueFollowsTransitionsRatherThanUniqueTrackIdsAndStopsCycles() =
        runBlocking {
            SpotifyCanvasClient.resetAccountState()
            val machine =
                buildJsonObject {
                    put(
                        "tracks",
                        buildJsonArray {
                            add(track(first))
                            add(track(second))
                        },
                    )
                    put(
                        "states",
                        buildJsonArray {
                            for ((trackIndex, next) in listOf(0 to 1, 1 to 2, 1 to 3, 0 to 1)) {
                                add(
                                    buildJsonObject {
                                        put("track", trackIndex)
                                        put(
                                            "transitions",
                                            buildJsonObject { put("show_next", buildJsonObject { put("state_index", next) }) },
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            SpotifyCanvasClient.cacheWebPlayerAutoplayQueue(machine, 0)
            assertEquals(
                listOf(second, second, first),
                SpotifyCanvasClient.cachedWebPlayerQueue(first)!!.map { it.id.substringAfterLast(':') },
            )
            val empty =
                buildJsonObject {
                    put(
                        "player_state",
                        buildJsonObject {
                            put("track", track(first))
                            put("next_tracks", buildJsonArray {})
                        },
                    )
                }
            assertTrue(SpotifyCanvasClient.cacheSpotifyConnectClusterQueue(empty, first))
            assertEquals(emptyList(), SpotifyCanvasClient.cachedWebPlayerQueue(first))
            SpotifyCanvasClient.resetAccountState()
        }

    @Test fun playbackObserverReportsRealTransitionsPauseSeekAndAdjacentDuplicatesOnlyWhenEnabled() {
        val events = mutableListOf<String>()
        val observer =
            PlaybackHistoryObserver(
                object : HistoryActions {
                    override fun start(
                        track: MediaMetadata,
                        context: String?,
                    ) {
                        events += "start:${track.id}"
                    }

                    override fun state(
                        playing: Boolean,
                        track: MediaMetadata,
                        context: String?,
                    ) {
                        events += "playing:$playing"
                    }

                    override fun seek(
                        position: Long,
                        playing: Boolean,
                        track: MediaMetadata,
                        context: String?,
                    ) {
                        events += "seek:$position"
                    }

                    override fun stop() {
                        events += "stop"
                    }
                },
            )

        fun state(
            instance: String,
            position: Long,
            playing: Boolean = true,
        ) = PlaybackState(Track("spotify:track:$first", "Fixture", "Artist", "Album", 180000), playing, position).apply {
            playbackInstanceId =
                instance
        }
        observer.accept(state("one", 0), false, 0)
        assertTrue(events.isEmpty())
        observer.accept(PlaybackState(state("one", 0).track, true), true, 0)
        assertTrue(events.isEmpty())
        observer.accept(state("one", 0), true, 0)
        observer.accept(state("one", 1000), true, 1000)
        observer.accept(state("one", 1000, false), true, 1000)
        observer.accept(state("one", 50000, false), true, 1100)
        observer.accept(state("one", 50000), true, 1100)
        observer.accept(state("two", 0), true, 1200)
        observer.accept(state("two", 1000), false, 2200)
        observer.accept(state("two", 2000), false, 3200)
        assertEquals(
            listOf(
                "start:spotify:track:$first",
                "playing:false",
                "seek:50000",
                "playing:true",
                "stop",
                "start:spotify:track:$first",
                "stop",
            ),
            events,
        )
    }

    @Test fun nativeCatalogUsesMillisecondsAndLyricsKeepsSubsecondSync() {
        val song =
            SongItem(
                "spotify:track:$first",
                "Fixture",
                listOf(Artist("Artist")),
                duration = 180,
                thumbnail = "https://i.scdn.co/image/fixture",
            )
        assertEquals(180000L, song.track().duration)
        assertEquals(180, song.track().legacy().duration)
        assertEquals(listOf(1230L, 21005L), parseLrc("[00:01.23]First\n[00:21.005]Second").map { it.timeMs })
    }

    @Test fun capturedCookieRetainsPersonalizationWithoutTransmittingCookieAttributes() {
        val normalized =
            normalizeSpotifyCookieInput("Cookie: sp_dc=fixture-session; Path=/; Secure; sp_t=fixture-personalization; HttpOnly")
        assertEquals("sp_dc=fixture-session; sp_t=fixture-personalization", normalized)
        assertNull(normalizeSpotifyCookieInput("sp_t=fixture-personalization"))
        assertNull(normalizeSpotifyCookieInput("bad\r\nheader"))
    }

    @Test fun publishedPlaybackStateConstructorAndCopyDescriptorsRemainAvailable() {
        val constructor =
            PlaybackState::class.java.getConstructor(
                Track::class.java,
                Boolean::class.javaPrimitiveType,
                Long::class.javaPrimitiveType,
                List::class.java,
            )
        assertNotNull(constructor)
        val value =
            PlaybackState(Track("id", "title", "artist", "album")).apply {
                currentIndex = 2
                playbackInstanceId = "occurrence"
            }
        val encoded = Json.encodeToJsonElement(value).jsonObject
        assertEquals(2, encoded["currentIndex"]!!.jsonPrimitive.int)
        assertEquals("occurrence", encoded["playbackInstanceId"]!!.jsonPrimitive.content)
        val replay =
            PlaybackState(value.track).apply {
                currentIndex = 2
                playbackInstanceId = "replay"
            }
        assertNotEquals(value, replay)
        assertNotEquals(value.hashCode(), replay.hashCode())
        assertNotNull(PlaybackState::class.java.methods.firstOrNull { it.name == "copy" && it.parameterCount == 4 })
    }
}

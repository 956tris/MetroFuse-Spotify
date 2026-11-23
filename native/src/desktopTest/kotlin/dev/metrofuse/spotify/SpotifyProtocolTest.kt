package dev.metrofuse.spotify

import dev.metrofuse.api.PlaybackState
import dev.metrofuse.api.LyricsRequest
import dev.metrofuse.api.PluginContext
import dev.metrofuse.api.PluginSettings
import dev.metrofuse.api.Track
import dev.metrofuse.api.TrackIdentity
import dev.metrofuse.api.WebLoginRequest
import dev.metrofuse.sdk.acceptsBrowserNavigation
import dev.metrofuse.sdk.captureBrowserCookies
import dev.metrofuse.spotify.legacy.Artist
import dev.metrofuse.spotify.legacy.MediaMetadata
import dev.metrofuse.spotify.legacy.SongItem
import dev.metrofuse.spotify.legacy.SpotifyCanvasClient
import dev.metrofuse.spotify.legacy.SpotifyHomeFeedParser
import dev.metrofuse.spotify.legacy.normalizeSpotifyCookieInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.lang.reflect.Proxy
import kotlin.test.*

class SpotifyProtocolTest {
    @Test fun lyricsProviderSkipsLookupWhileSignedOut() = runBlocking {
        val settings = object : PluginSettings {
            override val values = MutableStateFlow<Map<String, JsonElement>>(emptyMap())
            override suspend fun set(key: String, value: JsonElement) = error("Unexpected settings write")
        }
        val contextType = PluginContext::class.java
        val context = contextType.cast(
            Proxy.newProxyInstance(contextType.classLoader, arrayOf(contextType)) { _, method, _ ->
                check(method.name == "getSettings") { "Unexpected host call: ${method.name}" }
                settings
            },
        )
        val catalog = SpotifyCatalog(SpotifyPlugin(), context)
        assertNull(catalog.lyrics(LyricsRequest(TrackIdentity("fixture"), "Track", "Artist", 180_000)))
    }

    @Test fun optionalMetadataAccountSkipsLookupWhileSignedOut() = runBlocking {
        val plugin = SpotifyPlugin()
        assertNull(plugin.accountIfSignedIn { fail("Signed-out metadata lookup must skip the provider") })
        assertFailsWith<IllegalStateException> { plugin.account { fail("Account-only lookup requires sign-in") } }
        Unit
    }

    @Test fun hostTrackPreservesSpotifyIdentityWithoutProviderExtras() = runBlocking {
        val uri = "spotify:track:" + "1".repeat(22)
        val hostTrack = Track("metrofuse:metrofuse.spotify:$uri", "Fixture", "Artist", "", 180_000)
        assertEquals(uri, SpotifyCanvasClient.resolveTrackUriForHistory(hostTrack.legacy(), "sp_dc=fixture"))
    }

    @Test fun dealerConnectionIdCanArriveWithoutAUri() {
        val message = buildJsonObject {
            put("type", "message")
            put("headers", buildJsonObject { put("Spotify-Connection-Id", "connection") })
        }
        assertEquals("connection", SpotifyCanvasClient.parseSpotifyDealerConnectionId(message))
    }

    @Test fun browserHandoffCapturesServiceCookiesWithoutAnExactCompletionRoute() {
        val plugin: dev.metrofuse.api.LoginPlugin = SpotifyPlugin()
        assertTrue(plugin is dev.metrofuse.api.NativeLoginUi)
        assertFalse(plugin is dev.metrofuse.api.WebLoginPlugin)
        val request = (plugin as SpotifyPlugin).browserRequest()
        val handedOff = Json.decodeFromString<WebLoginRequest>(Json.encodeToString(request))
        assertEquals(request, handedOff)
        assertNull(handedOff.completionPath)
        assertFalse("completionPath" in Json.parseToJsonElement(Json.encodeToString(request)).jsonObject)
        assertEquals("https://open.spotify.com/", handedOff.cookieUrl)
        assertEquals(listOf("sp_dc"), handedOff.requiredCookies)
        assertTrue(handedOff.acceptsBrowserNavigation("https://open.spotify.com/intl-en/"))
        assertTrue(handedOff.acceptsBrowserNavigation("https://open.spotify.com/collection/tracks"))
        assertTrue(handedOff.acceptsBrowserNavigation("https://account.apple.com/"))
        assertTrue(handedOff.acceptsBrowserNavigation("https://m.facebook.com/login.php"))
        assertFalse(handedOff.acceptsBrowserNavigation("https://open.spotify.com.evil.example/intl-en/"))
        assertFalse(handedOff.acceptsBrowserNavigation("http://open.spotify.com/"))
        assertNull(handedOff.captureBrowserCookies(""))
        assertNull(handedOff.captureBrowserCookies("sp_key=fixture"))
        assertNull(handedOff.captureBrowserCookies("sp_dc="))
        val authenticated = "sp_dc=fixture; sp_key=fixture"
        assertEquals(mapOf("cookie" to authenticated), handedOff.captureBrowserCookies(authenticated))
    }

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
                        initialPositionMs: Long,
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
            instance: String?,
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
                "start:spotify:track:$first",
                "stop",
            ),
            events,
        )
        events.clear()
        observer.accept(state(null, 0), true, 4000)
        assertTrue(events.isEmpty())
        observer.accept(state(null, 700), true, 4700)
        observer.accept(state("late", 1000), true, 5000)
        observer.accept(state(null, 1000, false), true, 5000)
        observer.accept(state("late", 1000), true, 5200)
        assertEquals(listOf("start:spotify:track:$first", "playing:false", "playing:true"), events)
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

    @Test fun homeChoosesTheLargestCoverAndNativeTracksUpgradeStandaloneThumbnails() {
        val largeCover = "https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36"
        val home = Json.parseToJsonElement(
            """
            {"sections":[{"title":"Recommended","items":[{"data":{
                "uri":"spotify:album:$first","name":"Album",
                "coverArt":{"sources":[
                    {"url":"https://i.scdn.co/image/small","width":64},
                    {"url":"","width":1000},
                    {"url":"$largeCover","height":640}
                ]}
            }}]}]}
            """.trimIndent(),
        ).jsonObject
        assertEquals(largeCover, SpotifyHomeFeedParser.parse(home).shelves().single().items.single().album?.artwork)

        val song = SongItem(
            "spotify:track:$first",
            "Fixture",
            listOf(Artist("Artist")),
            thumbnail = "spotify:image:ab67616d000048518863bc11d2aa12b54f5aeb36",
        )
        assertEquals(largeCover, song.track().artwork)
        val external = "https://example.com/image/ab67616d000048518863bc11d2aa12b54f5aeb36"
        assertEquals(external, song.copy(thumbnail = external).track().artwork)
    }

    @Test fun fullWebApiQueueKeepsOrderedOccurrencesWhenConnectOnlyReturnsOneTrack() = runBlocking {
        val nextId = "0000000000000000000002"
        fun entry(id: String) = buildJsonObject {
            put("id", id)
            put("uri", "spotify:track:$id")
            put("name", "Fixture")
            put("type", "track")
            putJsonArray("artists") { addJsonObject { put("name", "Artist") } }
            putJsonObject("album") {
                putJsonArray("images") { addJsonObject { put("url", "https://i.scdn.co/image/fixture") } }
            }
        }
        val full = buildJsonObject {
            put("currently_playing", entry(first))
            putJsonArray("queue") { add(entry(nextId)); add(entry(nextId)); add(entry(first)) }
        }
        val preview = buildJsonObject {
            putJsonObject("player_state") {
                put("track", entry(first))
                putJsonArray("next_tracks") { add(entry(nextId)) }
            }
        }
        try {
            assertFalse(SpotifyCanvasClient.cacheSpotifyWebApiQueue(full, nextId))
            assertTrue(SpotifyCanvasClient.cacheSpotifyWebApiQueue(full, first))
            assertTrue(SpotifyCanvasClient.cacheSpotifyConnectClusterQueue(preview, first))
            assertEquals(listOf(nextId, nextId, first), SpotifyCanvasClient.cachedWebPlayerQueue(first)?.map { it.id.substringAfterLast(':') })
        } finally {
            SpotifyCanvasClient.stopWebPlayerHistory()
        }
    }

    @Test fun queueSyncPreservesPlaylistAndExistingLocalQueue() {
        val track = Track("spotify:track:$first", "Fixture", "Artist", "")
        val next = track.copy(id = "another")
        val playback = PlaybackState(track, true, 0, listOf(track, next))
        assertFalse(canImportSpotifyQueue(playback, null))
        assertTrue(canImportSpotifyQueue(playback, playback.queue.map(Track::id)))
        val playlistTrack = track.copy(extras = mapOf("spotify.contextUri" to "spotify:playlist:fixture"))
        assertFalse(canImportSpotifyQueue(PlaybackState(playlistTrack, true, 0, listOf(playlistTrack)), null))
        assertTrue(canImportSpotifyQueue(PlaybackState(track, true, 0, listOf(track)), null))
    }

    @Test fun binaryQueueMetadataDecodesDurationArtistAndExplicitFlag() {
        val track = byteArrayOf(
            0x12, 0x04, 'S'.code.toByte(), 'o'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(),
            0x22, 0x05, 0x12, 0x03, 'A'.code.toByte(), 'c'.code.toByte(), 't'.code.toByte(),
            0x38, 0xc0.toByte(), 0xfc.toByte(), 0x15, 0x48, 0x01,
        )
        val song = assertNotNull(parseSpotifyQueueMetadata(first, track))
        assertEquals("spotify:track:$first", song.id)
        assertEquals("Song", song.title)
        assertEquals(listOf(Artist("Act")), song.artists)
        assertEquals(180, song.duration)
        assertTrue(song.explicit)
        assertEquals("0000000000000000000000000000000a", spotifyMetadataGid("000000000000000000000a"))
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

package dev.metrofuse.spotify

import dev.metrofuse.api.*
import dev.metrofuse.spotify.legacy.AlbumItem
import dev.metrofuse.spotify.legacy.ArtistItem
import dev.metrofuse.spotify.legacy.PlaylistItem
import dev.metrofuse.spotify.legacy.SongItem
import dev.metrofuse.spotify.legacy.SpotifyArtistOverview
import dev.metrofuse.spotify.legacy.SpotifyCanvasClient
import kotlinx.serialization.json.*

internal class SpotifyCatalog(
    private val plugin: SpotifyPlugin,
    private val context: PluginContext,
) : SearchProvider,
    CatalogProvider,
    LibraryProvider,
    MetadataSource,
    LyricsProvider {
    override val id = "spotify.catalog"
    override val supportsSongVotes = true
    override val supportsLikedSongs = true

    override suspend fun search(request: SearchRequest): SearchResult {
        if (request.query.isBlank()) return SearchResult(emptyList())
        return plugin.account { cookie ->
            val items =
                SpotifyCanvasClient
                    .resolveSearchSummaryPage(request.query, cookie)
                    ?.summaries
                    .orEmpty()
                    .flatMap { it.items }
            val limit = request.limit.coerceIn(1, 100)
            SearchResult(
                items.filterIsInstance<SongItem>().take(limit).map { it.track() },
                items.filterIsInstance<AlbumItem>().take(limit).map { it.album() },
                items.filterIsInstance<ArtistItem>().take(limit).map { it.artist() },
                playlists = items.filterIsInstance<PlaylistItem>().take(limit).map { it.playlist() },
            )
        }
    }

    override suspend fun album(id: String): CatalogAlbum? =
        plugin.account {
            SpotifyCanvasClient.resolveAlbum(id, it)?.let { page ->
                CatalogAlbum(
                    page.playlist.id,
                    page.playlist.title,
                    page.playlist.author
                        ?.name
                        .orEmpty(),
                    page.playlist.thumbnail.orEmpty(),
                    page.songs.map {
                        it.track(page.playlist.id)
                    },
                )
            }
        }

    override suspend fun artist(id: String): CatalogArtist? =
        plugin.account {
            val page = SpotifyCanvasClient.resolveArtist(id, it) ?: return@account null
            val overview = SpotifyCanvasClient.resolveArtistOverview(id, it)
            CatalogArtist(
                page.playlist.id,
                page.playlist.title,
                page.playlist.thumbnail.orEmpty(),
                page.songs.map {
                    it.track()
                },
                overview?.popularReleases.orEmpty().map { it.album() },
            )
        }

    override suspend fun playlist(id: String): CatalogPlaylist? =
        plugin.account {
            SpotifyCanvasClient.resolvePlaylist(id, it)?.let { page ->
                page.playlist.playlist(page.songs.map { it.track(page.playlist.id) })
            }
        }

    override suspend fun library(request: LibraryRequest): LibraryPage =
        plugin.account {
            val prefix = if (request.likedSongsOnly) "spotify-likes:" else "spotify-library:"
            val offset =
                request.continuation?.let { cursor ->
                    require(cursor.startsWith(prefix) && cursor.length < 80) { "INVALID_CONTINUATION" }
                    cursor.removePrefix(prefix).toInt().also { require(it >= 0) }
                } ?: 0
            val items =
                if (request.likedSongsOnly) {
                    SpotifyCanvasClient
                        .resolvePlaylist("spotify:collection:tracks", it)
                        ?.songs
                        .orEmpty()
                        .map { song ->
                            HomeItem(
                                track =
                                    song.track().let { track ->
                                        track.copy(
                                            extras =
                                                track.extras + ("likeStatus" to "LIKE"),
                                        )
                                    },
                            )
                        }
                } else {
                    SpotifyCanvasClient
                        .resolveLibraryPage(it)
                        ?.shelves()
                        .orEmpty()
                        .flatMap { shelf -> shelf.items }
                }
            require(offset <= items.size) { "INVALID_CONTINUATION" }
            val end = (offset + 100).coerceAtMost(items.size)
            LibraryPage(items.subList(offset, end), if (end < items.size) prefix + end else null, complete = true)
        }

    override suspend fun setSongVote(request: SongVoteRequest): Boolean =
        plugin.account {
            if (request.vote ==
                TrackVote.DISLIKE
            ) {
                false
            } else {
                SpotifyCanvasClient.setTrackLiked(request.trackId, it, request.vote == TrackVote.LIKE)
            }
        }

    override suspend fun resolve(request: MetadataRequest): MetadataContribution? =
        plugin.account { cookie ->
            val base =
                context.metadata.value.takeIf { it.id == request.identity.canonicalId } ?: Track(
                    request.identity.canonicalId,
                    request.base.fields["title"]
                        ?.value
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty(),
                    request.base.fields["artist"]
                        ?.value
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty(),
                    request.base.fields["album"]
                        ?.value
                        ?.jsonPrimitive
                        ?.contentOrNull
                        .orEmpty(),
                )
            val source = base.copy(id = request.identity.identifiers["spotify"] ?: base.id)
            val uri = SpotifyCanvasClient.resolveTrackUriForMix(source.legacy(), cookie) ?: return@account null
            val fields =
                buildMap<String, JsonElement> {
                    put("spotify.uri", JsonPrimitive(uri))
                    SpotifyCanvasClient.resolveTrackMetadata(uri, cookie)?.track()?.let { track ->
                        put("title", JsonPrimitive(track.title))
                        put("artist", JsonPrimitive(track.artist))
                        put("album", JsonPrimitive(track.album))
                        put("duration", JsonPrimitive(track.duration))
                        put("artwork", JsonPrimitive(track.artwork))
                        track.extras.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
                    }
                    SpotifyCanvasClient.resolveTrackIsrc(uri, cookie)?.let { put("isrc", JsonPrimitive(it)) }
                    if (enabled("mixMetadataEnabled")) {
                        SpotifyCanvasClient.resolveMixMetadata(source.legacy(), cookie)?.let { mix ->
                            mix.bpm?.let { put("bpm", JsonPrimitive(it)) }
                            mix.keySignature?.let { put("keySignature", JsonPrimitive(it)) }
                            mix.timeSignature?.let { put("timeSignature", JsonPrimitive(it)) }
                        }
                    }
                    if (enabled("canvasEnabled")) {
                        SpotifyCanvasClient.resolveBackground(source.legacy(), cookie)?.let { canvas ->
                            put("canvasUrl", JsonPrimitive(canvas.url))
                            put("canvasHeaders", buildJsonObject { canvas.headers.forEach { (key, value) -> put(key, value) } })
                            put("canvasProvider", JsonPrimitive("spotify"))
                        }
                    }
                }
            MetadataContribution(request.identity, fields, confidence = .9)
        }

    override suspend fun lyrics(request: LyricsRequest): LyricsResult? {
        if (!enabled("lyricsEnabled")) return null
        return plugin.account { cookie ->
            SpotifyCanvasClient
                .resolveLyrics(
                    request.title,
                    request.artist,
                    (request.duration / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    cookie,
                )?.let { result -> LyricsResult(parseLrc(result.lrc), "Spotify") }
        }
    }

    private fun enabled(key: String) =
        context.settings.values.value[key]
            ?.jsonPrimitive
            ?.booleanOrNull != false

    internal fun registerServices() {
        services(listOf("spotify.artist/v1", "catalog.artist-metadata/v1")) { request ->
            plugin.account { cookie ->
                SpotifyCanvasClient.resolveArtistOverview(request.getValue("id").jsonPrimitive.content, cookie)?.json()
                    ?: buildJsonObject {}
            }
        }
        service("spotify.history/v1") { request ->
            plugin.account { cookie ->
                buildJsonObject {
                    put(
                        "tracks",
                        Json.encodeToJsonElement(
                            SpotifyCanvasClient
                                .resolveListeningHistory(
                                    cookie,
                                    request["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 200) ?: 50,
                                ).map { it.track() },
                        ),
                    )
                }
            }
        }
        services(listOf("spotify.canvas/v1", "artwork.canvas/v1")) { request ->
            plugin.account { cookie ->
                val track = request["track"]?.let { Json.decodeFromJsonElement<Track>(it) } ?: context.player.state.value.track
                val canvas = SpotifyCanvasClient.resolveBackground(track.legacy(), cookie)
                buildJsonObject {
                    canvas?.let {
                        put("url", it.url)
                        put(
                            "headers",
                            buildJsonObject {
                                it.headers.forEach { (key, value) ->
                                    put(key, value)
                                }
                            },
                        )
                    }
                }
            }
        }
        service("spotify.queue/v1") { request ->
            plugin.account { cookie ->
                val track = request["track"]?.let { Json.decodeFromJsonElement<Track>(it) } ?: context.player.state.value.track
                val tracks =
                    SpotifyCanvasClient.resolveWebPlayerQueue(
                        track.legacy(),
                        cookie,
                        limit = request["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 1000) ?: 1000,
                        refresh = request["refresh"]?.jsonPrimitive?.booleanOrNull != false,
                    )
                buildJsonObject {
                    put("tracks", Json.encodeToJsonElement(tracks.orEmpty().map { it.track() }))
                    put("source", "spotify-web-player")
                    put("complete", tracks != null)
                    put("includesCurrent", false)
                }
            }
        }
        service("spotify.recommendations/v1") { request ->
            plugin.account { cookie ->
                val track = request["track"]?.let { Json.decodeFromJsonElement<Track>(it) } ?: context.player.state.value.track
                buildJsonObject {
                    put(
                        "tracks",
                        Json.encodeToJsonElement(
                            SpotifyCanvasClient
                                .resolveAutoplayRecommendations(
                                    track.legacy(),
                                    cookie,
                                    limit =
                                        request["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 25,
                                ).map { it.track() },
                        ),
                    )
                }
            }
        }
        service("spotify.releases/v1") {
            plugin.account { cookie ->
                buildJsonObject {
                    put(
                        "albums",
                        Json.encodeToJsonElement(SpotifyCanvasClient.resolveNewReleasesForFollowedArtists(cookie).map { it.album.album() }),
                    )
                }
            }
        }
    }

    private fun service(
        name: String,
        call: suspend (JsonObject) -> JsonObject,
    ) = context.registerService(
        name,
        object : JsonService {
            override suspend fun call(request: JsonObject) = call.invoke(request)
        },
    )

    private fun services(
        names: List<String>,
        call: suspend (JsonObject) -> JsonObject,
    ) = names.forEach { service(it, call) }
}

internal fun parseLrc(value: String): List<LyricsLine> =
    value
        .lineSequence()
        .mapNotNull { line ->
            val match = Regex("^\\[(\\d+):(\\d+)(?:[.:](\\d+))?](.*)$").matchEntire(line) ?: return@mapNotNull null
            val fraction =
                match.groupValues[3]
                    .padEnd(3, '0')
                    .take(3)
                    .toLongOrNull() ?: 0
            LyricsLine(match.groupValues[4], match.groupValues[1].toLong() * 60_000 + match.groupValues[2].toLong() * 1000 + fraction)
        }.toList()

internal fun SpotifyArtistOverview.json() =
    buildJsonObject {
        biography?.let { put("biography", it) }
        put("popularReleases", Json.encodeToJsonElement(popularReleases.map { it.album() }))
        put("relatedArtists", Json.encodeToJsonElement(relatedArtists.map { it.artist() }))
        put("featuring", Json.encodeToJsonElement(featuring.map { it.album() }))
        put("playlists", Json.encodeToJsonElement(artistPlaylists.map { it.playlist() }))
        put("discoveredOn", Json.encodeToJsonElement(discoveredOn.map { it.playlist() }))
        put("featuringPlaylists", Json.encodeToJsonElement(featuringPlaylists.map { it.playlist() }))
        put(
            "concerts",
            buildJsonArray {
                concerts.forEach { concert ->
                    add(
                        buildJsonObject {
                            put("title", concert.title)
                            put("uri", concert.uri)
                            concert.city?.let { put("city", it) }
                            concert.venue?.let { put("venue", it) }
                            concert.startDateIso?.let { put("startDateIso", it) }
                        },
                    )
                }
            },
        )
        put(
            "merch",
            buildJsonArray {
                merch.forEach { item ->
                    add(
                        buildJsonObject {
                            put("name", item.name)
                            put("url", item.url)
                            item.price?.let { put("price", it) }
                            item.imageUrl?.let { put("imageUrl", it) }
                        },
                    )
                }
            },
        )
    }

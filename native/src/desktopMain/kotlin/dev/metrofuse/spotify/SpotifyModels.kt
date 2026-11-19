package dev.metrofuse.spotify

import dev.metrofuse.api.CatalogAlbum
import dev.metrofuse.api.CatalogArtist
import dev.metrofuse.api.CatalogPlaylist
import dev.metrofuse.api.HomeItem
import dev.metrofuse.api.HomeShelf
import dev.metrofuse.api.Track
import dev.metrofuse.spotify.legacy.AlbumItem
import dev.metrofuse.spotify.legacy.ArtistItem
import dev.metrofuse.spotify.legacy.MediaMetadata
import dev.metrofuse.spotify.legacy.PlaylistItem
import dev.metrofuse.spotify.legacy.SongItem
import dev.metrofuse.spotify.legacy.YTItem

internal fun SongItem.track(contextUri: String? = null): Track =
    Track(
        id,
        title,
        artists.joinToString(", ") { it.name },
        album?.name.orEmpty(),
        duration?.times(1000L) ?: 0,
        thumbnail,
        buildMap {
            put("provider", "spotify")
            contextUri?.let { put("spotify.contextUri", it) }
            put("spotify.uri", id)
            put("explicit", explicit.toString())
            album?.let { put("albumId", it.id) }
            artists.mapNotNull { it.id }.takeIf { it.isNotEmpty() }?.let { put("artistIds", it.joinToString(",")) }
        },
    )

internal fun AlbumItem.album() = CatalogAlbum(id, title, artists.orEmpty().joinToString(", ") { it.name }, thumbnail)

internal fun ArtistItem.artist() = CatalogArtist(id, title, thumbnail.orEmpty())

internal fun PlaylistItem.playlist(tracks: List<Track> = emptyList()) =
    CatalogPlaylist(id, title, author?.name.orEmpty(), thumbnail.orEmpty(), tracks)

internal fun YTItem.item(): HomeItem =
    when (this) {
        is SongItem -> HomeItem(track = track())
        is AlbumItem -> HomeItem(album = album())
        is ArtistItem -> HomeItem(artist = artist())
        is PlaylistItem -> HomeItem(playlist = playlist())
    }

internal fun dev.metrofuse.spotify.legacy.HomePage.shelves() = sections.map { HomeShelf(it.title, it.items.map(YTItem::item)) }

internal fun Track.legacy() =
    MediaMetadata(
        extras["providerTrackId"] ?: extras["spotify.uri"] ?: id.removePrefix("metrofuse:metrofuse.spotify:"),
        title,
        listOf(
            dev.metrofuse.spotify.legacy
                .Artist(artist),
        ),
        (duration / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        artwork.takeIf(String::isNotBlank),
        extras["albumId"]?.let {
            dev.metrofuse.spotify.legacy
                .Album(album, it)
        },
        extras["explicit"] == "true",
    )

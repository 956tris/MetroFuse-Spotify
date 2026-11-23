package dev.metrofuse.spotify

import dev.metrofuse.spotify.legacy.Album
import dev.metrofuse.spotify.legacy.Artist
import dev.metrofuse.spotify.legacy.SongItem
import dev.metrofuse.spotify.legacy.SpotifyCanvasClient
import java.math.BigInteger

private const val SPOTIFY_BASE62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
private val base = BigInteger.valueOf(62)

internal fun spotifyMetadataGid(id: String): String {
    val gid = id.fold(BigInteger.ZERO) { value, char ->
        value * base + BigInteger.valueOf(SPOTIFY_BASE62.indexOf(char).toLong())
    }
    return gid.toString(16).padStart(32, '0')
}

private fun ByteArray.metadataUri(type: String): String? {
    if (size != 16) return null
    var value = BigInteger(1, this)
    val digits = StringBuilder()
    while (value.signum() != 0) {
        val (quotient, remainder) = value.divideAndRemainder(base)
        digits.append(SPOTIFY_BASE62[remainder.toInt()])
        value = quotient
    }
    return "spotify:$type:${digits.reverse().toString().padStart(22, '0')}"
}

internal fun parseSpotifyQueueMetadata(trackId: String, bytes: ByteArray): SongItem? {
    val track = SpotifyCanvasClient.parseProtoMessageOrNull(bytes) ?: return null
    val title = track.string(2)?.takeIf(String::isNotBlank) ?: return null
    val album = track.firstMessage(3)
    val covers = album?.firstMessage(17)?.messages(1).orEmpty() + album?.messages(9).orEmpty()
    val cover = covers.filter { it.firstBytes(1)?.isNotEmpty() == true }
        .maxByOrNull { image -> image.int(3)?.div(2)?.takeIf { it > 0 } ?: image.int(2) ?: 0 }
        ?.firstBytes(1)?.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return SongItem(
        id = "spotify:track:$trackId",
        title = title,
        artists = track.messages(4).mapNotNull { artist ->
            val name = artist.string(2) ?: return@mapNotNull null
            Artist(name, artist.firstBytes(1)?.metadataUri("artist"))
        },
        album = album?.string(2)?.let { Album(it, album.firstBytes(1)?.metadataUri("album").orEmpty()) },
        duration = track.int(7)?.div(2000),
        thumbnail = cover?.let { "https://i.scdn.co/image/$it" }.orEmpty(),
        explicit = track.bool(9),
    )
}

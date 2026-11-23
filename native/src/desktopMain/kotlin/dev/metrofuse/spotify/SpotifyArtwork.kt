package dev.metrofuse.spotify

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

private val spotifyImageUri = Regex("spotify:image:([a-f0-9]{40})", RegexOption.IGNORE_CASE)
private val spotifySmallAlbumCover =
    Regex("(https://(?:i\\.scdn\\.co|image-cdn-fa\\.spotifycdn\\.com)/image/ab67616d0000)(?:1e02|4851)([a-f0-9]{24})", RegexOption.IGNORE_CASE)

internal fun String.spotifyCoverUrl(): String {
    val url = spotifyImageUri.matchEntire(this)?.let { "https://i.scdn.co/image/${it.groupValues[1]}" } ?: this
    // Album covers use the same image ID suffix at 64, 300 and 640 px.
    val cover = spotifySmallAlbumCover.matchEntire(url) ?: return url
    return "${cover.groupValues[1]}b273${cover.groupValues[2]}"
}

internal fun JsonArray.bestSpotifyImageUrl(): String? =
    mapNotNull { element ->
        val image = element as? JsonObject ?: return@mapNotNull null
        val url =
            (image["url"] as? JsonPrimitive)?.contentOrNull?.takeIf {
                it.startsWith("https://", ignoreCase = true) ||
                    it.startsWith("http://", ignoreCase = true) ||
                    spotifyImageUri.matches(it)
            } ?: return@mapNotNull null
        val size = (image["width"] as? JsonPrimitive)?.longOrNull ?: (image["height"] as? JsonPrimitive)?.longOrNull ?: 0L
        url to size
    }.maxByOrNull { it.second }?.first

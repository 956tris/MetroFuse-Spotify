/** Ported from MetroFuse, GPL-3.0. See SOURCE for provenance. */
package dev.metrofuse.spotify.legacy
import kotlinx.serialization.json.*
import java.net.URLDecoder
import dev.metrofuse.spotify.legacy.Album as TubeAlbum
import dev.metrofuse.spotify.legacy.Artist as TubeArtist

object SpotifyHomeFeedParser {
    fun parse(root: JsonObject): HomePage {
        val sections =
            extractSections(root)
                .mapNotNull(::parseSection)
                .distinctBy { section -> section.title to section.items.joinToString("|") { it.id } }

        return HomePage(chips = null, sections = sections)
    }

    private fun extractSections(root: JsonObject): List<JsonElement> {
        val data = root.obj("data")
        return buildList {
            data?.obj("homeSections")?.array("sections")?.let(::addAll)
            data
                ?.obj("home")
                ?.obj("sectionContainer")
                ?.obj("sections")
                ?.array("items")
                ?.let(::addAll)
            data?.obj("home")?.spotifyArrayAt("sections")?.let(::addAll)
            data?.obj("home")?.spotifyArrayAt("items")?.let(::addAll)
            data
                ?.obj("homeSection")
                ?.obj("sectionContainer")
                ?.obj("sections")
                ?.array("items")
                ?.let(::addAll)
            root
                .obj("home")
                ?.obj("sectionContainer")
                ?.obj("sections")
                ?.array("items")
                ?.let(::addAll)
            root
                .obj("sectionContainer")
                ?.obj("sections")
                ?.array("items")
                ?.let(::addAll)
            root.collectSpotifySections(this)
        }.distinct()
    }

    private fun parseSection(element: JsonElement): HomePage.Section? {
        val section = element.obj ?: return null
        val data = section.obj("data")

        val title =
            data?.spotifySectionTitle()
                ?: section.spotifySectionTitle()
                ?: return null
        val items =
            section
                .spotifySectionItemElements()
                .mapNotNull(::parseItem)
                .distinctBy { it.id }

        return if (items.isEmpty()) {
            null
        } else {
            HomePage.Section(
                title = title,
                label = data?.spotifySectionSubtitle() ?: section.spotifySectionSubtitle(),
                thumbnail = null,
                items = items,
            )
        }
    }

    private fun parseItem(element: JsonElement): YTItem? {
        val item = element.obj ?: return null
        val candidates = item.spotifyContentCandidates()
        val fallbackUri = candidates.firstNotNullOfOrNull { it.spotifyUri() }

        candidates.forEach { candidate ->
            val uri = candidate.spotifyUri() ?: fallbackUri
            val type = candidate.spotifyContentType(uri) ?: return@forEach
            val parsed =
                when (type) {
                    "Track" -> candidate.toSpotifySong(uri)
                    "Album", "PreRelease" -> candidate.toSpotifyAlbum(uri)
                    "Playlist", "PseudoPlaylist" -> candidate.toSpotifyPlaylist(uri)
                    "Artist" -> candidate.toSpotifyArtist(uri)
                    else -> null
                }
            if (parsed != null) {
                return parsed
            }
        }
        return null
    }

    private fun JsonObject.toSpotifySong(uri: String?): SongItem? {
        val id = spotifyId(uri) ?: return null
        val title = string("name") ?: return null
        val album = obj("albumOfTrack") ?: obj("album")
        val thumbnail = album?.spotifyArtworkUrl() ?: spotifyArtworkUrl() ?: return null
        val artists = spotifyArtists().ifEmpty { album?.spotifyArtists().orEmpty() }

        return SongItem(
            id = "spotify:track:$id",
            title = title,
            artists = artists,
            album =
                album?.let {
                    TubeAlbum(
                        name = it.string("name") ?: return@let null,
                        id = "spotify:album:${it.spotifyId(it.string("uri")) ?: return@let null}",
                    )
                },
            duration =
                obj("duration")?.long("totalMilliseconds")?.div(1000)?.toInt()
                    ?: obj("trackDuration")?.long("totalMilliseconds")?.div(1000)?.toInt()
                    ?: long("durationMs")?.div(1000)?.toInt(),
            thumbnail = thumbnail,
            explicit = isSpotifyExplicit(),
        )
    }

    private fun JsonObject.toSpotifyAlbum(uri: String?): AlbumItem? {
        val id = spotifyId(uri) ?: return null
        val title =
            string("name")
                ?: obj("preReleaseContent")?.string("name")
                ?: return null
        val content = obj("preReleaseContent") ?: this
        val thumbnail = content.spotifyArtworkUrl() ?: spotifyArtworkUrl() ?: return null

        return AlbumItem(
            browseId = "spotify:album:$id",
            playlistId = "spotify:album:$id",
            title = title,
            artists = content.spotifyArtists().takeIf { it.isNotEmpty() },
            year = content.obj("date")?.long("year")?.toInt() ?: content.string("releaseDate")?.take(4)?.toIntOrNull(),
            thumbnail = thumbnail,
            explicit = false,
        )
    }

    private fun JsonObject.toSpotifyPlaylist(uri: String?): PlaylistItem? {
        val id = spotifyId(uri) ?: return null
        val title = string("name") ?: return null
        val owner =
            obj("ownerV2")
                ?.obj("data")
                ?.let { it.string("displayName") ?: it.string("name") ?: it.string("username") }
                ?: obj("owner")
                    ?.let { it.string("displayName") ?: it.string("name") ?: it.string("username") }

        return PlaylistItem(
            id = "spotify:playlist:$id",
            title = title,
            author = owner?.let { TubeArtist(name = it, id = null) },
            songCountText =
                obj("content")?.long("totalCount")?.let { "$it songs" }
                    ?: obj("tracks")?.long("totalCount")?.let { "$it songs" },
            thumbnail = spotifyArtworkUrl() ?: obj("images")?.findSpotifyImageUrl(),
        )
    }

    private fun JsonObject.toSpotifyArtist(uri: String?): ArtistItem? {
        val id = spotifyId(uri) ?: return null
        val title =
            obj("profile")?.string("name")
                ?: string("name")
                ?: return null

        return ArtistItem(
            id = "spotify:artist:$id",
            title = title,
            thumbnail = spotifyArtworkUrl(),
        )
    }

    private fun JsonObject.spotifyId(uri: String?): String? =
        (string("id") ?: uri)
            ?.spotifyExternalId()

    private fun JsonObject.spotifyArtists(): List<TubeArtist> =
        (
            obj("artists")?.array("items")
                ?: array("artists")
        ).orEmpty()
            .mapNotNull { item ->
                val artist = item.obj ?: return@mapNotNull null
                val data = artist.obj("data") ?: artist
                val name =
                    data.obj("profile")?.string("name")
                        ?: data.string("name")
                        ?: return@mapNotNull null
                val id = data.string("id") ?: data.string("uri")?.substringAfterLast(':')
                TubeArtist(name = name, id = id?.let { "spotify:artist:$it" })
            }

    private fun JsonObject.spotifyArtworkUrl(): String? =
        listOf(
            obj("coverArt"),
            obj("image"),
            obj("images"),
            obj("visuals")?.obj("avatarImage"),
            obj("profile")?.obj("avatar"),
            obj("preReleaseContent")?.obj("coverArt"),
            obj("albumOfTrack")?.obj("coverArt"),
            obj("album")?.obj("coverArt"),
        ).firstNotNullOfOrNull { it?.findSpotifyImageUrl() }

    private fun JsonObject.findSpotifyImageUrl(depth: Int = 0): String? {
        if (depth > 6) return null

        string("url")
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let { return it }

        array("sources")
            ?.mapNotNull { it.obj?.string("url") }
            ?.firstOrNull { it.startsWith("http", ignoreCase = true) }
            ?.let { return it }

        array("items")
            ?.firstNotNullOfOrNull { it.obj?.findSpotifyImageUrl(depth + 1) }
            ?.let { return it }

        return listOf("coverArt", "image", "images", "visuals", "avatarImage", "avatar", "profile", "albumOfTrack")
            .firstNotNullOfOrNull { key -> obj(key)?.findSpotifyImageUrl(depth + 1) }
    }

    private fun JsonObject.spotifyLabel(): String? =
        string("transformedLabel")
            ?: string("text")
            ?: string("label")
            ?: string("name")

    private fun JsonObject.isSpotifyExplicit(): Boolean =
        obj("contentRating")
            ?.toString()
            ?.contains("EXPLICIT", ignoreCase = true) == true

    private fun JsonElement.collectSpotifySections(
        sections: MutableList<JsonElement>,
        depth: Int = 0,
    ) {
        if (depth > 8) return
        when (this) {
            is JsonObject -> {
                if (looksLikeSpotifySection()) {
                    sections.add(this)
                }
                values.forEach { it.collectSpotifySections(sections, depth + 1) }
            }

            is JsonArray -> {
                forEach { it.collectSpotifySections(sections, depth + 1) }
            }

            else -> {
                Unit
            }
        }
    }

    private fun JsonObject.looksLikeSpotifySection(): Boolean = spotifySectionTitle() != null && spotifySectionItemElements().isNotEmpty()

    private fun JsonObject.spotifySectionTitle(): String? =
        obj("title")?.spotifyLabel()
            ?: obj("header")?.obj("title")?.spotifyLabel()
            ?: obj("metadata")?.obj("title")?.spotifyLabel()
            ?: obj("sectionMetadata")?.obj("title")?.spotifyLabel()
            ?: obj("sectionInfo")?.obj("title")?.spotifyLabel()
            ?: obj("sectionInfo")?.spotifyLabel()
            ?: string("title")
            ?: string("sectionTitle")
            ?: string("headerTitle")
            ?: string("name")

    private fun JsonObject.spotifySectionSubtitle(): String? =
        obj("subtitle")?.spotifyLabel()
            ?: obj("header")?.obj("subtitle")?.spotifyLabel()
            ?: obj("metadata")?.obj("subtitle")?.spotifyLabel()
            ?: obj("sectionInfo")?.obj("subtitle")?.spotifyLabel()
            ?: string("subtitle")
            ?: string("description")

    private fun JsonObject.spotifySectionItemElements(): List<JsonElement> =
        listOfNotNull(
            spotifyArrayAt("sectionItems"),
            spotifyArrayAt("data", "sectionItems"),
            spotifyArrayAt("items"),
            spotifyArrayAt("data", "items"),
            spotifyArrayAt("contents"),
            spotifyArrayAt("data", "contents"),
            spotifyArrayAt("content"),
            spotifyArrayAt("data", "content"),
            spotifyArrayAt("cards"),
            spotifyArrayAt("data", "cards"),
            spotifyArrayAt("children"),
            spotifyArrayAt("data", "children"),
            spotifyArrayAt("components"),
            spotifyArrayAt("data", "components"),
            spotifyArrayAt("slots"),
            spotifyArrayAt("data", "slots"),
        ).firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun JsonObject.spotifyArrayAt(vararg path: String): JsonArray? {
        var current: JsonElement = this
        path.forEach { key ->
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return when (val value = current) {
            is JsonArray -> value
            is JsonObject -> value.array("items") ?: value.array("sections")
            else -> null
        }
    }

    private fun JsonObject.spotifyContentCandidates(): List<JsonObject> =
        buildList {
            collectSpotifyContentCandidates(this)
        }.distinct()

    private fun JsonObject.collectSpotifyContentCandidates(
        target: MutableList<JsonObject>,
        depth: Int = 0,
    ) {
        if (depth > 5) return
        target.add(this)
        listOf(
            "content",
            "contents",
            "item",
            "itemV2",
            "entity",
            "entityV2",
            "data",
            "card",
            "cards",
            "cardRepresentation",
            "representation",
            "children",
            "component",
            "components",
            "slot",
        ).forEach { key ->
            when (val child = this[key]) {
                is JsonObject -> child.collectSpotifyContentCandidates(target, depth + 1)
                is JsonArray -> child.forEach { it.obj?.collectSpotifyContentCandidates(target, depth + 1) }
                else -> Unit
            }
        }
    }

    private fun JsonObject.spotifyUri(): String? =
        string("uri")
            ?: string("entityUri")
            ?: string("targetUri")
            ?: obj("uri")?.string("uri")
            ?: obj("navigationAction")?.string("uri")
            ?: obj("navigationAction")?.obj("target")?.string("uri")
            ?: obj("target")?.string("uri")
            ?: obj("target")?.string("entityUri")

    private fun JsonObject.spotifyContentType(uri: String?): String? {
        val typeText =
            listOfNotNull(
                string("__typename"),
                string("type"),
                string("mediaType"),
                obj("data")?.string("__typename"),
            ).joinToString(" ")

        return when {
            typeText.contains("Track", ignoreCase = true) -> "Track"
            typeText.contains("Album", ignoreCase = true) -> "Album"
            typeText.contains("PreRelease", ignoreCase = true) -> "PreRelease"
            typeText.contains("Playlist", ignoreCase = true) -> "Playlist"
            typeText.contains("Artist", ignoreCase = true) -> "Artist"
            uri?.startsWith("spotify:track:", ignoreCase = true) == true -> "Track"
            uri?.startsWith("spotify:album:", ignoreCase = true) == true -> "Album"
            uri?.startsWith("spotify:playlist:", ignoreCase = true) == true -> "Playlist"
            uri?.startsWith("spotify:user:", ignoreCase = true) == true && uri.contains(":playlist:", ignoreCase = true) -> "Playlist"
            uri?.startsWith("spotify:artist:", ignoreCase = true) == true -> "Artist"
            else -> null
        }
    }
}

private fun String.spotifyExternalId(): String? {
    val decoded =
        runCatching { URLDecoder.decode(trim(), "UTF-8") }
            .getOrDefault(trim())
            .trim()
    if (decoded.isBlank()) return null

    val lower = decoded.lowercase()
    val urlMarkers =
        listOf(
            "/playlist/",
            "/album/",
            "/artist/",
            "/track/",
            "/mix/",
            "/collection/",
        )
    val fromUrl =
        urlMarkers.firstNotNullOfOrNull { marker ->
            lower
                .indexOf(marker)
                .takeIf { it >= 0 }
                ?.let { index -> decoded.substring(index + marker.length) }
        }
    val fromUri =
        decoded
            .takeIf { it.contains(':') && !it.startsWith("http", ignoreCase = true) }
            ?.substringAfterLast(':')

    return (fromUrl ?: fromUri ?: decoded)
        .substringBefore('?')
        .substringBefore('#')
        .trim()
        .trim('/')
        .substringBefore('/')
        .takeIf { it.isNotBlank() && it != "null" }
}

private val JsonElement.obj: JsonObject?
    get() = this as? JsonObject

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject

private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)
        ?.contentOrNull
        ?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)
        ?.longOrNull

private fun JsonObject.boolean(name: String): Boolean =
    (this[name] as? JsonPrimitive)
        ?.booleanOrNull == true

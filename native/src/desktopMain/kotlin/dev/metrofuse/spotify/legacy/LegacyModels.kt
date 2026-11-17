package dev.metrofuse.spotify.legacy

data class Artist(
    val name: String,
    val id: String? = null,
)

data class Album(
    val name: String,
    val id: String,
) {
    val title: String get() = name
}

sealed class YTItem {
    abstract val id: String
    abstract val title: String
    abstract val thumbnail: String?
    abstract val explicit: Boolean
}

data class SongItem(
    override val id: String,
    override val title: String,
    val artists: List<Artist>,
    val album: Album? = null,
    val duration: Int? = null,
    val musicVideoType: String? = null,
    val chartPosition: Int? = null,
    val chartChange: String? = null,
    override val thumbnail: String,
    override val explicit: Boolean = false,
    val setVideoId: String? = null,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    val historyRemoveToken: String? = null,
    val isEpisode: Boolean = false,
    val uploadEntityId: String? = null,
) : YTItem()

data class AlbumItem(
    val browseId: String,
    val playlistId: String,
    override val id: String = browseId,
    override val title: String,
    val artists: List<Artist>?,
    val year: Int? = null,
    override val thumbnail: String,
    override val explicit: Boolean = false,
) : YTItem()

data class PlaylistItem(
    override val id: String,
    override val title: String,
    val author: Artist?,
    val songCountText: String?,
    override val thumbnail: String?,
    val isEditable: Boolean = false,
    val isPodcast: Boolean = false,
) : YTItem() {
    override val explicit: Boolean get() = false
}

data class ArtistItem(
    override val id: String,
    override val title: String,
    override val thumbnail: String?,
    val channelId: String? = null,
    val isProfile: Boolean = false,
) : YTItem() {
    override val explicit: Boolean get() = false
}

data class HomePage(
    val chips: List<String>?,
    val sections: List<Section>,
    val continuation: String? = null,
) {
    data class Section(
        val title: String,
        val label: String?,
        val thumbnail: String?,
        val items: List<YTItem>,
    )
}

data class SearchSummary(
    val title: String,
    val items: List<YTItem>,
)

data class SearchSummaryPage(
    val summaries: List<SearchSummary>,
)

data class ExternalPlaylistPage(
    val playlist: PlaylistItem,
    val songs: List<SongItem>,
    val continuation: String? = null,
)

data class MediaMetadata(
    val id: String,
    val title: String,
    val artists: List<Artist>,
    val duration: Int,
    val thumbnailUrl: String? = null,
    val album: Album? = null,
    val explicit: Boolean = false,
    val isEpisode: Boolean = false,
    val isVideoSong: Boolean = false,
)

object ProviderIsrc {
    fun normalize(value: String?): String? =
        value
            ?.trim()
            ?.uppercase()
            ?.replace(Regex("[^A-Z0-9]"), "")
            ?.takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }

    fun firstOf(value: String?): String? = normalize(value)
}

object IsrcResolver {
    var publish: (String, String, String, Int?) -> Unit = { _, _, _, _ -> }

    fun publish(
        title: String,
        artist: String,
        isrc: String,
        duration: Int?,
    ) = publish.invoke(title, artist, isrc, duration)
}

/** Only fixed diagnostic text is logged: HTTP bodies, headers and credentials stay private. */
object Timber {
    var sink: (String) -> Unit = {}

    fun w(
        message: String,
        vararg values: Any?,
    ) = sink("Spotify network diagnostic")

    fun w(
        error: Throwable?,
        message: String,
        vararg values: Any?,
    ) = w(message)

    fun e(
        error: Throwable?,
        message: String,
        vararg values: Any?,
    ) = w(message)

    fun i(
        message: String,
        vararg values: Any?,
    ) = w(message)

    fun d(
        message: String,
        vararg values: Any?,
    ) = w(message)

    fun d(
        error: Throwable?,
        message: String,
        vararg values: Any?,
    ) = w(message)

    fun tag(value: String) = this
}

from pathlib import Path
import shutil
import re
import argparse

root = Path(__file__).resolve().parents[1]
template = root.parent / 'MetroFuse-Deezer'
arguments = argparse.ArgumentParser(description='Historical extraction tool; overwrites manual adaptations. Do not run on the final port.')
arguments.add_argument('--legacy', type=Path, required=True, help='Legacy MetroFuse checkout at revision 5ba186773')
legacy = arguments.parse_args().legacy.resolve(strict=True)
for name in ['gradlew', 'gradlew.bat', 'gradle.properties', '.gitignore', 'LICENSE', 'settings.gradle.kts', 'build.gradle.kts', 'native/build.gradle.kts', 'native/AndroidManifest.xml']:
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(template / name, target)
shutil.copytree(template / 'gradle', root / 'gradle', dirs_exist_ok=True)
for name in ['settings.gradle.kts', 'native/build.gradle.kts', 'native/AndroidManifest.xml']:
    path = root / name
    path.write_text(path.read_text().replace('Deezer', 'Spotify').replace('deezer', 'spotify'), encoding='utf-8')
build = root / 'native/build.gradle.kts'
build.write_text(build.read_text().replace('libs.ktor.client.core\n', 'libs.ktor.client.core,\n        libs.okhttp\n'), encoding='utf-8')
catalog = root / 'gradle/libs.versions.toml'
catalog.write_text(catalog.read_text().replace('[libraries]', '[libraries]\nokhttp = { module = "com.squareup.okhttp3:okhttp", version = "5.3.2" }'), encoding='utf-8')
output = root / 'native/src/desktopMain/kotlin/dev/metrofuse/spotify/legacy'
output.mkdir(parents=True, exist_ok=True)
source = legacy / 'app/src/main/kotlin/com/metrolist/music/utils/spotify/SpotifyCanvasClient.kt'
text = source.read_text(encoding='utf-8')
text = text[:text.index('/** Shared HTTP client for canvas video streams')] + text[text.index('data class SpotifyMixMetadata'):]
text = re.sub(r'^import (?:android\.|androidx\.|com\.metrolist\.|timber\.).*\n', '', text, flags=re.M)
text = text.replace('package com.metrolist.music.utils.spotify', 'package dev.metrofuse.spotify.legacy')
text = re.sub(r'^\s*(?:endpoint|playEndpoint|shuffleEndpoint|radioEndpoint) = null,?\n', '\n', text, flags=re.M)
(output / 'SpotifyCanvasClient.kt').write_text(text, encoding='utf-8')
parser = (legacy / 'app/src/main/kotlin/com/metrolist/music/providers/ExternalHomeFeedProviders.kt').read_text(encoding='utf-8')
parser = parser[parser.index('object SpotifyHomeFeedParser'):parser.index('object ExternalHomeItemIds')] + parser[parser.index('private fun String.spotifyExternalId'):]
parser = re.sub(r'^\s*(?:endpoint|playEndpoint|shuffleEndpoint|radioEndpoint) = null,?\n', '\n', parser, flags=re.M)
(output / 'SpotifyHomeFeedParser.kt').write_text('''/** Ported from MetroFuse, GPL-3.0. See SOURCE for provenance. */
package dev.metrofuse.spotify.legacy
import kotlinx.serialization.json.*
import java.net.URLDecoder
''' + parser, encoding='utf-8')
models = '''package dev.metrofuse.spotify.legacy

data class Artist(val name: String, val id: String? = null)
data class Album(val name: String, val id: String)
sealed class YTItem {
    abstract val id: String
    abstract val title: String
    abstract val thumbnail: String?
    abstract val explicit: Boolean
}
data class SongItem(override val id: String, override val title: String,
    val artists: List<Artist>, val album: Album? = null, val duration: Int? = null,
    val musicVideoType: String? = null, val chartPosition: Int? = null, val chartChange: String? = null,
    override val thumbnail: String, override val explicit: Boolean = false,
    val setVideoId: String? = null, val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null, val historyRemoveToken: String? = null,
    val isEpisode: Boolean = false, val uploadEntityId: String? = null): YTItem()
data class AlbumItem(val browseId: String, val playlistId: String,
    override val id: String = browseId, override val title: String, val artists: List<Artist>?,
    val year: Int? = null, override val thumbnail: String, override val explicit: Boolean = false): YTItem()
data class PlaylistItem(override val id: String, override val title: String,
    val author: Artist?, val songCountText: String?, override val thumbnail: String?,
    val isEditable: Boolean = false, val isPodcast: Boolean = false): YTItem() {
    override val explicit: Boolean get() = false
}
data class ArtistItem(override val id: String, override val title: String,
    override val thumbnail: String?, val channelId: String? = null, val isProfile: Boolean = false): YTItem() {
    override val explicit: Boolean get() = false
}
data class HomePage(val chips: List<String>?, val sections: List<Section>, val continuation: String? = null) {
    data class Section(val title: String, val label: String?, val thumbnail: String?, val items: List<YTItem>)
}
data class SearchSummary(val title: String, val items: List<YTItem>)
data class SearchSummaryPage(val summaries: List<SearchSummary>)
data class ExternalPlaylistPage(val playlist: PlaylistItem, val songs: List<SongItem>, val continuation: String? = null)
data class MediaMetadata(val id: String, val title: String, val artists: List<Artist>, val duration: Int,
    val thumbnailUrl: String? = null, val album: Album? = null, val explicit: Boolean = false)

object ProviderIsrc {
    fun normalize(value: String): String? = value.trim().uppercase().takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }
}
object IsrcResolver {
    var publish: (String, String, String, Int?) -> Unit = { _, _, _, _ -> }
    fun publish(title: String, artist: String, isrc: String, duration: Int?) = publish.invoke(title, artist, isrc, duration)
}
/** Only fixed diagnostic text is logged: HTTP bodies, headers and credentials stay private. */
object Timber {
    var sink: (String) -> Unit = {}
    fun w(message: String, vararg values: Any?) = sink(message.substringBefore(": %"))
    fun w(error: Throwable, message: String, vararg values: Any?) = w(message)
    fun e(error: Throwable, message: String, vararg values: Any?) = w(message)
    fun i(message: String, vararg values: Any?) = w(message)
    fun d(message: String, vararg values: Any?) = w(message)
}
'''
(output / 'LegacyModels.kt').write_text(models, encoding='utf-8')
(root / 'SOURCE').write_text('''Spotify metadata, Home, library, Canvas, lyrics, history, matching and account client ported from:
https://github.com/956tris/MetroFuse
Legacy checkout: MetroFuse-main-push, revision 5ba186773
app/src/main/kotlin/com/metrolist/music/utils/spotify/SpotifyCanvasClient.kt
app/src/main/kotlin/com/metrolist/music/providers/ExternalHomeFeedProviders.kt (Spotify parser only)
GPL-3.0; original Metrolist/MetroFuse contributor notices retained.
Android Canvas player UI is replaced by host-owned cross-platform rendering.
Plugin packaging follows MetroFuse-Deezer; host API/SDK and shared libraries are compile-only.
''', encoding='utf-8')
shutil.copyfile(root / 'SOURCE', root / 'native/SOURCE')
print('Ported full legacy Spotify client and Home parser into plugin-owned models.')

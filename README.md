# MetroFuse Spotify

[Repository](https://github.com/956tris/MetroFuse-Spotify) · [Plugin releases](https://github.com/956tris/MetroFuse-Spotify/releases)

Spotify metadata and account plugin, ported from the legacy non-KMP MetroFuse checkout on disk. Packages: `spotify.mfp` (JavaScript, Android/desktop), `spotify.jar` (native desktop) and `spotify.apk` (native Android plugin). Install one variant through MetroFuse Plugins and sign in using its account screen. These are plugin packages; the APK is imported by MetroFuse rather than installed as a standalone app.

The catalog supplies personalized Home, search, full album/playlist collections, artist details, saved library, liked tracks and like/unlike actions. Metadata includes track/artist/album artwork, duration, explicit flags, Spotify IDs and ISRC when returned. Optional enrichment provides tempo/key, synced lyrics and Canvas. Artist services provide biography, related artists, featuring releases, playlists, concerts and merchandise when Spotify supplies them. The host renders available Canvas and artist links.

The native client and listening-history manager are adapted directly from legacy MetroFuse. JavaScript ports its protocols, with GraphQL-first library/catalog access and REST fallbacks. Native browser login captures the authenticated Spotify session; JavaScript supports browser/device authorization and saved sessions. Credentials stay in the host's account storage and are excluded from diagnostics.

`Report listening history to Spotify` and `Use Spotify Web Player upcoming queue` are independent and off by default. Reporting observes actual starts, pauses, seeks and playback occurrences. Queue synchronization reads Web Player state, preserves repeated tracks and ordering, and changes only upcoming host entries when the server's current track matches. It does not substitute recommendation results for the queue. Native rejects incomplete/malformed snapshots and caps accepted queue snapshots at 1000 entries.

Spotify supplies metadata here; choose an audio provider separately in MetroFuse. Spotify stream decryption/DRM playback is not provided. Feature availability depends on account permissions, market and Spotify responses. Missing Canvas, lyrics, tempo/key or artist fields remain absent. Some private Spotify endpoints and persisted operation hashes can change; authenticated online behavior still needs a real-account validation pass. JavaScript has GraphQL Home; the legacy Casita Home fallback is native only.

## Host compatibility

Use the patched MetroFuse **preview002** host released with this plugin. Earlier builds also identify themselves as 10.0.0, so the app version alone cannot distinguish compatibility. JavaScript requires the new binary HTTP/WebSocket bridge, locale information and lifecycle cancellation. Upcoming queue reconciliation uses the optional `UpcomingQueuePlayer` interface; the original API 1.1.0 constructors remain binary compatible. Native login additionally uses the platform-built SDK `PluginAlertDialog` entry point. Native packages use the matching Kotlin/Compose ABI embedded in their manifest. Older published hosts do not provide all required extensions.

## Build and checks

Build matching host API/SDK desktop JARs first, then run from this repository (PowerShell):

```powershell
$env:ANDROID_HOME = '/path/to/android-sdk'
$hostCheckout = '/path/to/MetroFuse'
.\gradlew.bat :native:desktopTest :native:packagePluginJar :native:packagePluginApk "-PmetrofuseApiJar=$hostCheckout/plugin-api/build/libs/plugin-api-desktop-1.1.0.jar" "-PmetrofuseSdkJar=$hostCheckout/plugin-sdk/build/libs/plugin-sdk-desktop-1.1.0.jar" --offline --no-configuration-cache --max-workers=1
node --test tests/test_spotify.mjs
python tooling/package.py
python tests/check_packages.py
```

The native build uses compile-only host libraries and D8 for Android. The desktop package includes the SDK's Windows login helper; Android delegates to the host WebView. Package checks reject bundled host/runtime classes and desktop Skiko references in the Android DEX. `dist/SHA256SUMS` describes the resulting files. Backend tests cover queue duplicates, current-track matching, playback reporting transitions, account cleanup, timestamp units, login protocol and ABI descriptors. Live sign-in and live reporting have not been validated with a Spotify account.

## Source and license

GPL-3.0. See `LICENSE`, `native/SOURCE` and `javascript/SOURCE`. Legacy source revision: `5ba186773`, `MetroFuse-main-push`. The port retains plugin-owned legacy models, not bundled host classes. `tooling/port_legacy.py` records the initial extraction; do not rerun it over the manually adapted implementation.

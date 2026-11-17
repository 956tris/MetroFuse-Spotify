package dev.metrofuse.spotify

import androidx.compose.runtime.Composable
import dev.metrofuse.api.*
import dev.metrofuse.spotify.legacy.SpotifyCanvasClient
import dev.metrofuse.spotify.legacy.Timber
import dev.metrofuse.spotify.legacy.normalizeSpotifyCookieInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class SpotifyPlugin :
    NativePlugin,
    WebLoginPlugin,
    NativeLoginUi,
    HomePlugin {
    private val state = MutableStateFlow(PluginLoginState())
    override val loginState get() = state
    private val lock = Mutex()

    @Volatile private var cookie: String? = null
    private var revision = 0L
    private var screen: SpotifyLoginScreen? = null
    private var strings: Map<String, String> = emptyMap()
    private var catalog: SpotifyCatalog? = null
    private var host: PluginContext? = null
    private var history: SpotifyPlaybackHistory? = null

    internal fun session(): String = cookie ?: throw IllegalStateException("LOGIN_REQUIRED")

    internal suspend fun <T> account(block: suspend (String) -> T): T {
        val snapshot = session()
        val value = block(snapshot)
        if (cookie !== snapshot) throw CancellationException("Spotify account changed")
        return value
    }

    override suspend fun loginRequest() =
        LoginRequest(listOf(LoginField("cookie", strings.getValue("session"))), strings.getValue("instructions"))

    override suspend fun webLoginRequest() = browserRequest()

    override suspend fun login(credentials: LoginCredentials): PluginLoginState {
        val candidate = normalizeSpotifyCookieInput(credentials.values["cookie"].orEmpty()) ?: error("INVALID_CREDENTIALS")
        val generation = lock.withLock { ++revision }
        val user = withContext(Dispatchers.IO) { SpotifyCanvasClient.resolveAccountInfo(candidate) } ?: error("ACCOUNT_VERIFICATION_FAILED")
        return lock.withLock {
            if (generation != revision) throw CancellationException("Spotify login replaced")
            history?.close()
            SpotifyCanvasClient.resetAccountState()
            cookie = candidate
            history = host?.let { SpotifyPlaybackHistory(it, candidate) { cookie } }
            state.value = PluginLoginState(true, user.name)
            state.value
        }
    }

    override suspend fun logout() =
        lock.withLock {
            ++revision
            history?.close()
            history = null
            cookie = null
            state.value = PluginLoginState()
            SpotifyCanvasClient.resetAccountState()
        }

    @Composable override fun Content(context: LoginUiContext) {
        screen?.Content(context)
    }

    override suspend fun getHome(request: HomeRequest) =
        if (cookie ==
            null
        ) {
            emptyList()
        } else {
            account { SpotifyCanvasClient.resolveHomePage(it)?.shelves().orEmpty() }
        }

    override suspend fun start(context: PluginContext) {
        check(catalog == null)
        host = context
        Timber.sink = context::log
        strings =
            (Json.parseToJsonElement(context.resources.readAsset("assets/strings.json").toString(Charsets.UTF_8)) as JsonObject)
                .mapValues { it.value.jsonPrimitive.content }
        screen = SpotifyLoginScreen(browserRequest(), strings, context.resources)
        val provider = SpotifyCatalog(this, context).also { catalog = it }
        context.registerSearchProvider(provider)
        context.registerCatalogProvider(provider)
        context.registerMetadataSource(provider)
        context.registerLyricsProvider(provider)
        provider.registerServices()
    }

    override suspend fun stop() {
        logout()
        screen = null
        catalog = null
        host = null
        Timber.sink = {}
    }

    internal fun browserRequest() =
        WebLoginRequest(
            url = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F",
            cookieUrl = "https://open.spotify.com/",
            cookieField = "cookie",
            requiredCookies = listOf("sp_dc"),
            completionPath = "/",
            allowedHosts =
                listOf(
                    "accounts.spotify.com",
                    "open.spotify.com",
                    "spotify.com",
                    "www.spotify.com",
                    "accounts.google.com",
                    "www.google.com",
                    "appleid.apple.com",
                    "www.facebook.com",
                    "facebook.com",
                ),
        )
}

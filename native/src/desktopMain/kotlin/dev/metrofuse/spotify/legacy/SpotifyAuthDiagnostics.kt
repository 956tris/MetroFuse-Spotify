package dev.metrofuse.spotify.legacy

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/** Fixed stage names and status codes only; never log URLs, cookies, tokens or response bodies. */
internal class SpotifyAuthDiagnostics : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        val stage = when (url.host to url.encodedPath) {
            "api.github.com" to "/gists/22ed9c6ba463899e933427f7de1f0eef" -> "token configuration"
            "open.spotify.com" to "/api/server-time" -> "server time"
            "open.spotify.com" to "/api/token" -> "web token"
            "api.spotify.com" to "/v1/me" -> "account"
            "accounts.spotify.com" to "/oauth2/device/authorize" -> "device authorization"
            "accounts.spotify.com" to "/pair/api/resolve" -> "device confirmation"
            "accounts.spotify.com" to "/api/token" -> "device token"
            else -> null
        }
        return try {
            chain.proceed(chain.request()).also { response ->
                if (stage != null) Timber.sink("Spotify authentication: $stage HTTP ${response.code}")
            }
        } catch (error: IOException) {
            if (stage != null) Timber.sink("Spotify authentication: $stage network failure (${error.javaClass.simpleName})")
            throw error
        }
    }
}

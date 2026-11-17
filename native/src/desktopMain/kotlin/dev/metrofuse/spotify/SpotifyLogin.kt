package dev.metrofuse.spotify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.metrofuse.api.LoginUiContext
import dev.metrofuse.api.NativeLoginUi
import dev.metrofuse.api.PluginResources
import dev.metrofuse.api.WebLoginRequest
import dev.metrofuse.sdk.PluginAlertDialog
import dev.metrofuse.sdk.WebViewLabels
import dev.metrofuse.sdk.WebViewLoginScreen
import dev.metrofuse.sdk.WebViewLoginUi
import dev.metrofuse.spotify.legacy.normalizeSpotifyCookieInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

internal class SpotifyLoginScreen(
    private val request: WebLoginRequest,
    private val strings: Map<String, String>,
    private val resources: PluginResources,
) : NativeLoginUi {
    private val sdkScreen =
        WebViewLoginScreen(
            WebViewLoginUi(
                request,
                WebViewLabels(
                    strings.getValue("title"),
                    strings.getValue("instructions"),
                    strings.getValue("error"),
                    strings.getValue("retry"),
                    strings.getValue("cancel"),
                    strings.getValue("login"),
                    strings.getValue("logout"),
                ),
            ),
        )

    @Composable
    override fun Content(context: LoginUiContext) {
        if (!System.getProperty("os.name").startsWith("Windows") || context.account.loggedIn || !context.ready) {
            sdkScreen.Content(context)
            return
        }
        val submit by rememberUpdatedState(context.submit)
        var attempt by remember { mutableIntStateOf(0) }
        var manual by remember { mutableStateOf(false) }
        var token by remember { mutableStateOf("") }
        var running by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        var captured by remember { mutableStateOf(false) }
        LaunchedEffect(request, attempt, manual) {
            if (manual) return@LaunchedEffect
            running = true
            failed = false
            captured = false
            try {
                val header =
                    withTimeout(5 * 60_000L) {
                        runSpotifyBrowser(resources, request, strings.getValue("title"))
                    }
                val arl = normalizeSpotifyCookieInput(header) ?: throw IllegalStateException("INVALID_CREDENTIALS")
                captured = true
                submit(mapOf(request.cookieField to arl))
            } catch (cancelled: CancellationException) {
                if (cancelled !is TimeoutCancellationException) throw cancelled
                failed = true
            } catch (_: Exception) {
                failed = true
            } finally {
                running = false
            }
        }
        PluginAlertDialog(
            onDismissRequest = context.dismiss,
            title = { Text(strings.getValue("title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (manual) {
                        Text(strings.getValue("sessionHint"))
                        OutlinedTextField(
                            value = token,
                            onValueChange = { token = it.take(65536) },
                            label = { Text(strings.getValue("session")) },
                            enabled = !context.busy,
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(strings.getValue(if (running) "browserOpened" else "browserOpening"))
                    }
                    if (running || context.busy) CircularProgressIndicator()
                    if (failed) Text(strings.getValue("browserUnavailable"))
                    if (context.failed) Text(strings.getValue("error"))
                    TextButton(enabled = !context.busy, onClick = { manual = !manual }) {
                        Text(strings.getValue(if (manual) "useBrowser" else "useSession"))
                    }
                }
            },
            confirmButton = {
                if (manual) {
                    TextButton(
                        enabled = !context.busy && normalizeSpotifyCookieInput(token) != null,
                        onClick = {
                            submit(mapOf(request.cookieField to token.trim()))
                            token = ""
                        },
                    ) { Text(strings.getValue("login")) }
                } else if (failed || (captured && context.failed)) {
                    TextButton(enabled = !running && !context.busy, onClick = { attempt++ }) {
                        Text(strings.getValue("retry"))
                    }
                }
            },
            dismissButton = { TextButton(onClick = context.dismiss) { Text(strings.getValue("cancel")) } },
        )
    }
}

/** Load the helper from this plugin's assets, independently of the host SDK's resources. */
private suspend fun runSpotifyBrowser(
    resources: PluginResources,
    request: WebLoginRequest,
    title: String,
): String =
    withContext(Dispatchers.IO) {
        val directory = Files.createTempDirectory("metrofuse-spotify-login-").toFile()
        var process: Process? = null
        try {
            val executable = File(directory, "spotify-browser.exe")
            executable.writeBytes(resources.readAsset("assets/windows/spotify-browser.exe"))
            val requestFile = File(directory, "request.json")
            requestFile.writeText(spotifyBrowserRequestJson(request))
            val builder =
                ProcessBuilder(executable.absolutePath, requestFile.absolutePath, title, File(directory, "profile").absolutePath)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
            builder.environment()["DOTNET_BUNDLE_EXTRACT_BASE_DIR"] = File(directory, "runtime").absolutePath
            val browser = builder.start().also { process = it }
            val output =
                coroutineScope {
                    val reader =
                        async(Dispatchers.IO) {
                            browser.inputStream.use { input ->
                                val bytes = ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (bytes.size() + count > 131_072) throw IllegalStateException("INVALID_BROWSER_RESPONSE")
                                    bytes.write(buffer, 0, count)
                                }
                                bytes.toString(Charsets.UTF_8.name())
                            }
                        }
                    try {
                        while (browser.isAlive) delay(100)
                        if (browser.exitValue() != 0) throw IllegalStateException("BROWSER_UNAVAILABLE")
                        reader.await()
                    } finally {
                        if (browser.isAlive) browser.destroyForcibly()
                    }
                }
            val root = Json.parseToJsonElement(output) as? JsonObject ?: throw IllegalStateException("INVALID_BROWSER_RESPONSE")
            (root["values"] as? JsonObject)?.get(request.cookieField)?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("INVALID_BROWSER_RESPONSE")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                process?.let {
                    if (it.isAlive) it.destroyForcibly()
                    it.waitFor(2, TimeUnit.SECONDS)
                }
                // Only this newly created private directory is removed, after the browser exits.
                repeat(40) {
                    if (!directory.exists() || directory.deleteRecursively()) return@withContext
                    delay(250)
                }
                directory.deleteOnExit()
            }
        }
    }

internal fun spotifyBrowserRequestJson(request: WebLoginRequest): String = Json.encodeToString(request)

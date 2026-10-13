package finance.shilling.shared.data.sync

import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath

internal const val SECURE_SIGNALING_REQUIRED =
    "Sync requires an HTTPS server URL with a trusted TLS certificate, including for local development."

/** Validate before connecting; never silently upgrade or fall back to an insecure endpoint. */
internal fun secureServerUrl(value: String): Url {
    val input = value.trim()
    require(input.startsWith("https://", ignoreCase = true) &&
        input.substringAfter("://").substringBefore('/').isNotBlank() &&
        input.none { it.isWhitespace() || it.code < 32 || it == '\\' } &&
        '?' !in input && '#' !in input) { SECURE_SIGNALING_REQUIRED }
    val url = try {
        Url(input)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(SECURE_SIGNALING_REQUIRED)
    }
    require(url.protocol == URLProtocol.HTTPS && url.host.isNotBlank() &&
        url.user == null && url.password == null) { SECURE_SIGNALING_REQUIRED }
    return url
}

internal fun secureSignalingUrl(serverUrl: String): String {
    val url = secureServerUrl(serverUrl)
    return URLBuilder(url).apply {
        protocol = URLProtocol.WSS
        encodedPath = url.encodedPath.trimEnd('/') + "/ws/signal"
    }.buildString()
}

/** A redirect must never turn a TLS signaling handshake into a cleartext request. */
internal fun HttpClient.signalingTransport(): HttpClient = config {
    followRedirects = false
}

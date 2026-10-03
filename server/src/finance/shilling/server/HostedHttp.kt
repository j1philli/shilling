package finance.shilling.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

internal val hostedHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(3))
    .build()
internal val hostedRequestTimeout: Duration = Duration.ofSeconds(5)
// Bound sockets/in-flight upstream work independently of Dispatchers.IO sizing.
private val hostedRequests = Semaphore(128)

class HostedUpstreamUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** Suspend without occupying an IO worker; bound headers AND complete response body. */
internal suspend fun HttpClient.sendHostedRequest(
    request: HttpRequest,
    timeout: Duration
): HttpResponse<String> {
    require(!timeout.isNegative && !timeout.isZero)
    try {
        return withTimeoutOrNull(timeout.toMillis().coerceAtLeast(1)) {
            hostedRequests.withPermit {
                sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            }
        } ?: throw HostedUpstreamUnavailableException("Hosted upstream request timed out")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: HostedUpstreamUnavailableException) {
        throw failure
    } catch (failure: Exception) {
        throw HostedUpstreamUnavailableException("Hosted upstream request failed", failure)
    }
}

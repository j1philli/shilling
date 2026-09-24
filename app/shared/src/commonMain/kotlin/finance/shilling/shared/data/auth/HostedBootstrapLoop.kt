package finance.shilling.shared.data.auth

import finance.shilling.core.auth.AuthMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HostedBootstrapLoop(
    private val retryDelay: (Int) -> Long = ::hostedBootstrapRetryDelay,
    private val delayFn: suspend (Long) -> Unit = { delay(it) }
) {
    private val mutex = Mutex()

    suspend fun run(
        initialAuthRuntime: AuthRuntime? = null,
        resolve: suspend (AuthRuntime?) -> StartupStateResolution,
        onResolution: suspend (StartupStateResolution) -> Unit = {}
    ): StartupStateResolution = mutex.withLock {
        var currentAuthRuntime = initialAuthRuntime
        var attempt = 0
        while (true) {
            val resolution = resolve(currentAuthRuntime)
            currentAuthRuntime = resolution.authRuntime
            onResolution(resolution)

            if (!shouldRetryHostedBootstrap(resolution.identity)) {
                return@withLock resolution
            }

            delayFn(retryDelay(attempt))
            attempt += 1
        }

        error("Hosted bootstrap loop exited unexpectedly")
    }
}

fun shouldRetryHostedBootstrap(identity: StartupIdentity): Boolean =
    identity.serverConfig.authMode != AuthMode.NONE &&
        identity.bootstrapStatus.phase != HostedBootstrapPhase.READY

fun hostedBootstrapRetryDelay(attempt: Int): Long =
    when (attempt) {
        0 -> 2_000L
        1 -> 5_000L
        2 -> 15_000L
        else -> 30_000L
    }

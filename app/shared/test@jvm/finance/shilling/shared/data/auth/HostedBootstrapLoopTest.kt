package finance.shilling.shared.data.auth

import finance.shilling.core.auth.AuthMode
import finance.shilling.core.auth.ServerConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class HostedBootstrapLoopTest {
    @Test
    fun retriesUntilReadyThenStops() = runBlocking {
        val delays = mutableListOf<Long>()
        val loop = HostedBootstrapLoop(delayFn = { delays += it })
        var callCount = 0

        val finalResolution = loop.run(resolve = {
            callCount += 1
            when (callCount) {
                1 -> waitingResolution(HostedBootstrapPhase.WAITING_FOR_SERVER)
                2 -> waitingResolution(HostedBootstrapPhase.WAITING_FOR_SUPABASE)
                else -> readyResolution()
            }
        })

        assertEquals(3, callCount)
        assertEquals(listOf(2_000L, 5_000L), delays)
        assertEquals(HostedBootstrapPhase.READY, finalResolution.identity.bootstrapStatus.phase)
    }

    @Test
    fun serializesConcurrentRuns() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val loop = HostedBootstrapLoop(delayFn = {})
        var callCount = 0
        var activeResolvers = 0
        var maxConcurrentResolvers = 0

        suspend fun resolve(): StartupStateResolution {
            activeResolvers += 1
            maxConcurrentResolvers = maxOf(maxConcurrentResolvers, activeResolvers)
            callCount += 1
            if (callCount == 1) {
                started.complete(Unit)
                release.await()
            }
            activeResolvers -= 1
            return readyResolution()
        }

        val first = async { loop.run(resolve = { resolve() }) }
        started.await()
        val second = async { loop.run(resolve = { resolve() }) }
        delay(50)

        assertEquals(1, callCount)
        assertEquals(1, maxConcurrentResolvers)

        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(2, callCount)
        assertEquals(1, maxConcurrentResolvers)
    }

    private fun waitingResolution(phase: HostedBootstrapPhase): StartupStateResolution =
        StartupStateResolution(
            identity = StartupIdentity(
                serverConfig = ServerConfig(
                    authMode = AuthMode.SUPABASE,
                    supabaseUrl = "https://supabase.test",
                    supabaseAnonKey = "anon-key"
                ),
                authService = NoOpAuthService("device-1"),
                activeHouseholdId = "local-1",
                localHouseholdId = "local-1",
                hostedHouseholdId = null,
                bootstrapStatus = HostedBootstrapStatus(
                    phase = phase,
                    syncReady = false
                )
            ),
            authRuntime = AuthRuntime(
                serverConfig = ServerConfig(
                    authMode = AuthMode.SUPABASE,
                    supabaseUrl = "https://supabase.test",
                    supabaseAnonKey = "anon-key"
                ),
                authService = NoOpAuthService("device-1")
            )
        )

    private fun readyResolution(): StartupStateResolution =
        StartupStateResolution(
            identity = StartupIdentity(
                serverConfig = ServerConfig(
                    authMode = AuthMode.SUPABASE,
                    supabaseUrl = "https://supabase.test",
                    supabaseAnonKey = "anon-key"
                ),
                authService = NoOpAuthService("device-1"),
                activeHouseholdId = "hosted-1",
                localHouseholdId = "local-1",
                hostedHouseholdId = "hosted-1",
                bootstrapStatus = HostedBootstrapStatus(
                    phase = HostedBootstrapPhase.READY,
                    syncReady = true
                )
            ),
            authRuntime = AuthRuntime(
                serverConfig = ServerConfig(
                    authMode = AuthMode.SUPABASE,
                    supabaseUrl = "https://supabase.test",
                    supabaseAnonKey = "anon-key"
                ),
                authService = NoOpAuthService("device-1")
            )
        )
}

@file:OptIn(io.ktor.utils.io.ExperimentalKtorApi::class)
package finance.shilling.shared.data.backup

import finance.shilling.core.backup.BackupPolicy
import finance.shilling.shared.data.auth.NoOpAuthService
import finance.shilling.shared.data.store.backup.RecoveryKeyRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class BackupControllerTest {
    @Test fun enabledPolicyWaitsForPersistedRecoveryKeyBeforeBecomingVisible(): Unit = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gate = false
        var secret: String? = null
        val vault = object : BackupKeyVault {
            override suspend fun read(namespace: String): String? {
                if (gate) { entered.complete(Unit); release.await() }
                return secret
            }
            override suspend fun write(namespace: String, value: String) { secret = value }
            override suspend fun delete(namespace: String) { secret = null }
        }
        RecoveryKeyRepository(vault, "https://test|house").importKey(BackupCrypto().newKey(), saved = true)
        gate = true
        val http = HttpClient(MockEngine { respond(Json.encodeToString(BackupPolicy(enabled = true, available = true)),
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())) }) {
            install(ContentNegotiation) { json() }
        }
        val budget = CloudSyncTest.Budget("device")
        budget.init()
        val controller = BackupController(http, "https://test", "house", "device", NoOpAuthService("device"), vault,
            { error("Refreshing policy must not create WebRTC") }, this, budget.cloud)
        try {
            val refresh = async { controller.refresh() }
            withTimeout(5_000) { entered.await() }
            assertNull(controller.policy.value, "Do not trigger recovery UI while the vault is still loading")
            release.complete(Unit); refresh.await()
            assertTrue(controller.policy.value!!.enabled)
            assertTrue(controller.hasKey.value)
            assertTrue(controller.savedKey.value)
        } finally { release.complete(Unit); controller.close(); http.close(); budget.close() }
    }
}

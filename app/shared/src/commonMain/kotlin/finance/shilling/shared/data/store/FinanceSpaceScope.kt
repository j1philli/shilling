package finance.shilling.shared.data.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

const val LOCAL_SPACE_ID = "__local__"

/** A repository graph keeps this immutable identity for its entire lifetime. */
class FinanceSpaceScope(val id: String) {
    init { require(id.isNotBlank()) }
    private val writes = Mutex()
    @Volatile var active: Boolean = true
        private set

    /** Reentrant: a sync merge holds the lock while it writes through the stores. */
    internal suspend fun <T> write(action: suspend () -> T): T {
        if (coroutineContext[HeldWrite]?.scope === this) {
            check(active) { "This finance space was closed. Reopen the editor in the active space." }
            return action()
        }
        return writes.withLock {
            check(active) { "This finance space was closed. Reopen the editor in the active space." }
            withContext(HeldWrite(this)) { action() }
        }
    }

    private class HeldWrite(val scope: FinanceSpaceScope) : AbstractCoroutineContextElement(HeldWrite) {
        companion object Key : CoroutineContext.Key<HeldWrite>
    }

    /** Wait for a pending writer before retiring the old graph. Old editors cannot write afterward. */
    suspend fun retire() = writes.withLock { active = false }
}

/** Peer-supplied receipt IDs must remain single path components on native file stores. */
fun safeReceiptStorageId(id: String): String {
    require(id.isNotBlank() && id != "." && id != ".." && id.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid receipt identifier" }
    return id
}

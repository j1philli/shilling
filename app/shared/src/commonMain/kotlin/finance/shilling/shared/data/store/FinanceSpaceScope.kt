package finance.shilling.shared.data.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

const val LOCAL_SPACE_ID = "__local__"

/** A repository graph keeps this immutable identity for its entire lifetime. */
class FinanceSpaceScope(val id: String) {
    init { require(id.isNotBlank()) }
    private val writes = Mutex()
    @Volatile var active: Boolean = true
        private set

    internal suspend fun <T> write(action: suspend () -> T): T = writes.withLock {
        check(active) { "This finance space was closed. Reopen the editor in the active space." }
        action()
    }

    /** Wait for a pending writer before retiring the old graph. Old editors cannot write afterward. */
    suspend fun retire() = writes.withLock { active = false }
}

/** Peer-supplied receipt IDs must remain single path components on native file stores. */
fun safeReceiptStorageId(id: String): String {
    require(id.isNotBlank() && id != "." && id != ".." && id.none { it == '/' || it == '\\' || it == '\u0000' }) { "Invalid receipt identifier" }
    return id
}

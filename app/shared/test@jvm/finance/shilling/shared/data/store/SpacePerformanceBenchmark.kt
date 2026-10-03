package finance.shilling.shared.data.store

import finance.shilling.shared.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import app.cash.sqldelight.Query
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.mobilenativefoundation.store.store5.StoreWriteRequest
import kotlin.system.measureNanoTime

/** Matched synthetic Store5 workloads, outside timing-sensitive unit assertions. */
@OptIn(org.mobilenativefoundation.store.core5.ExperimentalStoreApi::class)
object SpacePerformanceBenchmark {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val driver = ListenerDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val f = LinkedTransferStoreTest.Fixture(driver).prepare()
        try {
            for (space in listOf("home", "business")) {
                f.graphs.activate(space)
                val seed = f.transfer().debit
                (0 until 50_000).chunked(1000).forEach { indices ->
                    f.graphs.current.postingsStore.write(StoreWriteRequest.of<PostingKey, List<Posting>, Unit>(
                        PostingKey.All, indices.map { seed.copy(id = "ordinary-$it", pairId = null, accountId = if (space == "home") "a" else "b") }))
                }
            }
            f.graphs.activate("home")
            f.transfers.save(f.transfer())
            val spaces = setOf("home", "business")
            suspend fun original(): List<LinkedTransfer> {
                val entries = spaces.flatMap { space -> createPostingStore(f.db, spaceId = space).readLocalSourceOfTruth(PostingKey.All).map { space to it } }
                return entries.filter { it.second.pairId?.startsWith(SPACE_TRANSFER_PREFIX) == true }
                    .groupBy { it.second.pairId!! }.mapNotNull { (link, legs) ->
                        val debit = legs.singleOrNull { it.second.id == "$link:debit" }
                        val credit = legs.singleOrNull { it.second.id == "$link:credit" }
                        if (debit != null && credit != null) LinkedTransfer(LinkedTransferKey(link, debit.first, credit.first), debit.second, credit.second) else null
                    }
            }
            repeat(3) { check(original() == f.transfers.list(spaces)) }
            repeat(12) { round ->
                for (variant in if (round % 2 == 0) listOf("original", "indexed") else listOf("indexed", "original")) {
                    val elapsed = measureNanoTime { check((if (variant == "original") original() else f.transfers.list(spaces)).single() == f.transfer()) }
                    println("SPACE_PROBE variant=$variant postings=100000 round=$round elapsedMs=${elapsed / 1_000_000.0}")
                }
            }
            repeat(100) { round ->
                val old = f.graphs.current
                val elapsed = measureNanoTime { f.graphs.activate(if (round % 2 == 0) "business" else "home") }
                check(!old.scope.active)
                check(f.graphs.current.accounts.getAll().size == 1)
                check(f.transfers.list(spaces).single() == f.transfer())
                println("SPACE_PROBE variant=switch round=$round elapsedMs=${elapsed / 1_000_000.0}")
            }
            withTimeout(5_000) { while (driver.activeListeners != 0) delay(10) }
            println("SPACE_PROBE variant=cleanup activeListeners=${driver.activeListeners}")
        } finally { f.close() }
    }

    private class ListenerDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        private val listeners = mutableMapOf<Query.Listener, MutableSet<String>>()
        val activeListeners: Int get() = synchronized(listeners) { listeners.size }
        override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
            synchronized(listeners) { listeners.getOrPut(listener) { mutableSetOf() }.addAll(queryKeys) }
            delegate.addListener(*queryKeys, listener = listener)
        }
        override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
            delegate.removeListener(*queryKeys, listener = listener)
            synchronized(listeners) {
                listeners[listener]?.removeAll(queryKeys.toSet())
                if (listeners[listener].isNullOrEmpty()) listeners.remove(listener)
            }
        }
    }
}

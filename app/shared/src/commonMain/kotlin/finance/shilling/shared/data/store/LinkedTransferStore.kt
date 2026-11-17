package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import finance.shilling.shared.data.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.*

const val SPACE_TRANSFER_PREFIX = "space-transfer:"
data class LinkedTransferKey(val linkId: String, val fromSpace: String, val toSpace: String)
data class LinkedTransfer(val key: LinkedTransferKey, val debit: Posting, val credit: Posting)

/** One local atomic command; only each space's own posting is broadcast to that space. */
@OptIn(ExperimentalStoreApi::class)
class LinkedTransferStore(
    private val db: ShillingDatabase,
    private val graphs: FinanceSpaceGraphs,
    private val ids: IdGenerator,
    private val notifier: ChangeNotifier
) {
    private val store = MutableStoreBuilder.from<LinkedTransferKey, List<LinkedTransfer>, List<LinkedTransfer>, List<LinkedTransfer>>(
        fetcher = Fetcher.of { _: LinkedTransferKey -> emptyList() },
        sourceOfTruth = SourceOfTruth.of(
            reader = { key -> combine(
                db.postingQueries.selectByPair(key.fromSpace, key.linkId).asFlow().map { it.awaitAsList().map { row -> row.toDomain() } },
                db.postingQueries.selectByPair(key.toSpace, key.linkId).asFlow().map { it.awaitAsList().map { row -> row.toDomain() } }
            ) { from, to ->
                val debit = from.singleOrNull { it.id == "${key.linkId}:debit" }
                val credit = to.singleOrNull { it.id == "${key.linkId}:credit" }
                if (debit != null && credit != null) listOf(LinkedTransfer(key, debit, credit)) else emptyList()
            } },
            writer = { key, transfers ->
                require(transfers.size == 1 && transfers.single().key == key)
                val transfer = transfers.single()
                graphs.writeAcrossSpaces(setOf(key.fromSpace, key.toSpace)) { active ->
                    validate(transfer)
                    val entries = listOf(key.fromSpace to transfer.debit, key.toSpace to transfer.credit)
                    db.transaction {
                        entries.forEach { (space, posting) ->
                            check(db.accountQueries.selectById(posting.accountId, space).awaitAsOneOrNull() != null) { "Choose an existing account in each space." }
                            val existing = db.postingQueries.selectById(posting.id, space).awaitAsOneOrNull()
                            check(existing == null || existing.pair_id == key.linkId) { "Transfer identifier conflicts with an existing transaction." }
                            db.postingQueries.upsert(posting.id, null, posting.type.name, posting.accountId, posting.date.toEpochDays().toLong(), posting.amount, key.linkId, posting.title, null, space).await()
                            versions(active, space).recordLocalUpsert(EntityType.POSTING, posting.id,
                                ChangePayload.PostingPayload(posting).toFields(), existing?.toDomain()?.let { ChangePayload.PostingPayload(it).toFields() })
                        }
                    }
                    publish(active, entries.map { (space, posting) -> space to posting.id })
                }
            },
            delete = { key -> graphs.writeAcrossSpaces(setOf(key.fromSpace, key.toSpace)) { active ->
                val current = storeRead(key)
                if (current == null && db.postingQueries.selectById("${key.linkId}:debit", key.fromSpace).awaitAsOneOrNull() == null && db.postingQueries.selectById("${key.linkId}:credit", key.toSpace).awaitAsOneOrNull() == null) return@writeAcrossSpaces
                check(current != null) { "Sync both spaces before deleting this linked transfer." }
                val legs = listOf(key.fromSpace to current.debit, key.toSpace to current.credit)
                db.transaction {
                    legs.forEach { (space, posting) ->
                        db.receiptQueries.detachByPosting(space, posting.id)
                        db.postingQueries.deleteById(posting.id, space)
                        versions(active, space).recordLocalDelete(EntityType.POSTING, posting.id, ChangePayload.PostingPayload(posting).toFields())
                    }
                }
                publish(active, legs.map { (space, posting) -> space to posting.id })
            } }
        ),
        converter = Converter.Builder<List<LinkedTransfer>, List<LinkedTransfer>, List<LinkedTransfer>>()
            .fromNetworkToLocal { it }.fromOutputToLocal { it }.build()
    ).disableCache().build(updater = Updater.by(post = { _, _ -> UpdaterResult.Success.Typed(Unit) }))

    private suspend fun storeRead(key: LinkedTransferKey): LinkedTransfer? {
        val debit = db.postingQueries.selectById("${key.linkId}:debit", key.fromSpace).awaitAsOneOrNull()?.toDomain()
        val credit = db.postingQueries.selectById("${key.linkId}:credit", key.toSpace).awaitAsOneOrNull()?.toDomain()
        return if (debit?.pairId == key.linkId && credit?.pairId == key.linkId) LinkedTransfer(key, debit, credit) else null
    }
    private val listStore = StoreBuilder.from<Set<String>, List<LinkedTransfer>, List<LinkedTransfer>>(
        fetcher = Fetcher.of { _: Set<String> -> error("Linked transfers are local-only") },
        sourceOfTruth = SourceOfTruth.of(
            reader = { spaces -> db.postingQueries.selectLinkedInSpaces(spaces).asFlow().map { query ->
                query.awaitAsList().groupBy { it.pair_id!! }.mapNotNull { (link, legs) ->
                    val debit = legs.singleOrNull { it.id == "$link:debit" }
                    val credit = legs.singleOrNull { it.id == "$link:credit" }
                    if (debit != null && credit != null && debit.space_id != credit.space_id)
                        LinkedTransfer(LinkedTransferKey(link, debit.space_id, credit.space_id), debit.toDomain(), credit.toDomain())
                    else null
                }
            }.flowOn(Dispatchers.Default) },
            writer = { _, _ -> error("Linked transfer lists are read-only") }
        )
    ).disableCache().build()

    suspend fun list(spaceIds: Set<String>): List<LinkedTransfer> {
        if (spaceIds.isEmpty()) return emptyList()
        return listStore.stream(StoreReadRequest.localOnly(spaceIds.toSet()))
            .filterIsInstance<StoreReadResponse.Data<List<LinkedTransfer>>>().first().value
    }
    suspend fun get(key: LinkedTransferKey): LinkedTransfer? = store.readLocalSourceOfTruth(key).singleOrNull()
    suspend fun save(transfer: LinkedTransfer) {
        store.writeLocally(StoreWriteRequest.of<LinkedTransferKey, List<LinkedTransfer>, Unit>(transfer.key, listOf(transfer)))
        notifier.notifyChanged()
    }
    suspend fun delete(key: LinkedTransferKey) { store.clear(key); notifier.notifyChanged() }
    private fun validate(transfer: LinkedTransfer) {
        val (key, debit, credit) = transfer
        require(key.linkId.startsWith(SPACE_TRANSFER_PREFIX) && key.linkId.length > SPACE_TRANSFER_PREFIX.length && key.fromSpace != key.toSpace)
        require(debit.id == "${key.linkId}:debit" && credit.id == "${key.linkId}:credit" && debit.pairId == key.linkId && credit.pairId == key.linkId)
        require(debit.type == ScheduleType.EXPENSE && credit.type == ScheduleType.INCOME && debit.scheduleId == null && credit.scheduleId == null)
        require(debit.amount.isFinite() && debit.amount > 0 && debit.amount == credit.amount && debit.date == credit.date && debit.title == credit.title)
    }
    private fun versions(active: FinanceSpaceGraph, space: String) =
        if (space == active.id) active.sync.versions else SyncVersions(db, space, active.sync.state.deviceId)
    private suspend fun publish(active: FinanceSpaceGraph, postings: List<Pair<String, String>>) {
        // An inactive space is caught up through its next WebRTC full-state snapshot.
        val peer = active.sync.state.peerSyncManager ?: return
        postings.filter { it.first == active.id }.forEach { (_, postingId) ->
            val state = active.sync.versions.load(EntityType.POSTING, postingId) ?: return@forEach
            runCatching { peer.broadcast(state.toChangeMessage(ids.newId(), EntityType.POSTING, postingId, active.sync.state.deviceId)) }
        }
    }
}

package finance.shilling.shared.data.store

import app.cash.sqldelight.async.coroutines.await

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import app.cash.sqldelight.coroutines.asFlow
import finance.shilling.shared.data.*
import finance.shilling.shared.data.sync.*
import finance.shilling.shared.db.ShillingDatabase
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import org.mobilenativefoundation.store.core5.ExperimentalStoreApi
import org.mobilenativefoundation.store.store5.*
import kotlin.time.Clock

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
                    val changes = entries.map { (space, posting) -> space to change(active, posting, ChangeOp.UPSERT) }
                    db.transaction {
                        entries.forEach { (space, posting) ->
                            check(db.accountQueries.selectById(posting.accountId, space).awaitAsOneOrNull() != null) { "Choose an existing account in each space." }
                            val existing = db.postingQueries.selectById(posting.id, space).awaitAsOneOrNull()
                            check(existing == null || existing.pair_id == key.linkId) { "Transfer identifier conflicts with an existing transaction." }
                            db.postingQueries.upsert(posting.id, null, posting.type.name, posting.accountId, posting.date.toEpochDays().toLong(), posting.amount, key.linkId, posting.title, null, space).await()
                        }
                        changes.forEach { (space, change) -> db.recordEntityChange(change, space) }
                    }
                    publish(active, changes)
                }
            },
            delete = { key -> graphs.writeAcrossSpaces(setOf(key.fromSpace, key.toSpace)) { active ->
                val current = storeRead(key)
                if (current == null && db.postingQueries.selectById("${key.linkId}:debit", key.fromSpace).awaitAsOneOrNull() == null && db.postingQueries.selectById("${key.linkId}:credit", key.toSpace).awaitAsOneOrNull() == null) return@writeAcrossSpaces
                check(current != null) { "Sync both spaces before deleting this linked transfer." }
                val changes = listOf(key.fromSpace to change(active, current.debit, ChangeOp.DELETE), key.toSpace to change(active, current.credit, ChangeOp.DELETE))
                db.transaction {
                    changes.forEach { (space, change) ->
                        db.receiptQueries.detachByPosting(space, change.entityId)
                        db.postingQueries.deleteById(change.entityId, space)
                        db.recordEntityChange(change, space)
                    }
                }
                publish(active, changes)
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
    suspend fun list(spaceIds: Set<String>): List<LinkedTransfer> {
        val entries = spaceIds.flatMap { space -> createPostingStore(db, spaceId = space).readLocalSourceOfTruth(PostingKey.All).map { space to it } }
        return entries.filter { it.second.pairId?.startsWith(SPACE_TRANSFER_PREFIX) == true }
            .groupBy { it.second.pairId!! }.mapNotNull { (link, legs) ->
                val debit = legs.singleOrNull { it.second.id == "$link:debit" }
                val credit = legs.singleOrNull { it.second.id == "$link:credit" }
                if (debit != null && credit != null) LinkedTransfer(LinkedTransferKey(link, debit.first, credit.first), debit.second, credit.second) else null
            }
    }
    suspend fun get(key: LinkedTransferKey): LinkedTransfer? = store.readLocalSourceOfTruth(key).singleOrNull()
    suspend fun save(transfer: LinkedTransfer) {
        store.write(StoreWriteRequest.of<LinkedTransferKey, List<LinkedTransfer>, Unit>(transfer.key, listOf(transfer)))
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
    private fun change(active: FinanceSpaceGraph, posting: Posting, op: ChangeOp) = ChangeMessage(
        id = ids.newId(), entityType = EntityType.POSTING, op = op, entityId = posting.id,
        timestamp = Clock.System.now().toEpochMilliseconds(), deviceId = active.sync.state.deviceId,
        payload = if (op == ChangeOp.UPSERT) ChangePayload.PostingPayload(posting) else null
    )
    private suspend fun publish(active: FinanceSpaceGraph, changes: List<Pair<String, ChangeMessage>>) {
        changes.filter { it.first == active.id }.forEach { (_, change) ->
            // An inactive space is caught up through its next WebRTC full-state snapshot.
            runCatching { active.sync.state.peerSyncManager?.broadcast(change) }
        }
    }
}

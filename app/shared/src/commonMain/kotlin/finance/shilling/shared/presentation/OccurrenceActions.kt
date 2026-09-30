package finance.shilling.shared.presentation

import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.ScheduledTxWithAccount
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ScheduleRepository

/** A completed action's confirmation message and how to undo it (UIs offer it in a snackbar/toast). */
class Undoable(val message: String, val undo: suspend () -> Unit)

/**
 * Occurrence/posting actions shared by Plan and transaction detail, each paired with an undo.
 */
class OccurrenceActions(
    private val postingRepo: PostingRepository,
    private val scheduleRepo: ScheduleRepository
) {
    suspend fun markPosted(item: ScheduledTxWithAccount): Undoable {
        postingRepo.recordFromOccurrence(item.tx)
        val base = "${item.tx.scheduleId}_${item.tx.date}"
        return Undoable("${item.tx.title} marked ${item.tx.type.postedLabel.lowercase()}") {
            deletePostingWithPartner(base)
            deletePostingWithPartner("${base}_dr")
        }
    }

    suspend fun unmarkPosted(item: ScheduledTxWithAccount): Undoable? {
        val postingId = item.postingId ?: return null
        deletePostingWithPartner(postingId)
        return Undoable("${item.tx.title} marked not ${item.tx.type.postedLabel.lowercase()}") {
            postingRepo.recordFromOccurrence(item.tx.copy(pairId = null))
        }
    }

    suspend fun skip(item: ScheduledTxWithAccount): Undoable {
        val previous = existingException(item)
        scheduleRepo.upsertException(
            ScheduleException(scheduleId = item.tx.scheduleId, date = item.tx.date, skip = true)
        )
        return Undoable("${item.tx.title} skipped for ${formatDate(item.tx.date)}") { restore(item, previous) }
    }

    suspend fun changeAmount(item: ScheduledTxWithAccount, amount: Double): Undoable {
        val previous = existingException(item)
        scheduleRepo.upsertException(
            ScheduleException(
                scheduleId = item.tx.scheduleId,
                date = item.tx.date,
                skip = false,
                overrideAmount = amount,
                overrideAccountId = item.tx.accountId,
                overrideCounterAccountId = item.tx.counterAccountId
            )
        )
        return Undoable("${item.tx.title} changed to ${formatCurrency(amount)} for ${formatDate(item.tx.date)}") {
            restore(item, previous)
        }
    }

    /** Deletes a posting and, for transfers, its other leg. */
    suspend fun deletePostingWithPartner(postingId: String) {
        val posting = postingRepo.getById(postingId) ?: return
        postingRepo.getTransferPartner(posting)?.let { postingRepo.delete(it.id) }
        postingRepo.delete(posting.id)
    }

    private suspend fun existingException(item: ScheduledTxWithAccount): ScheduleException? =
        scheduleRepo.getExceptions(listOf(item.tx.scheduleId))[item.tx.scheduleId]
            ?.firstOrNull { it.date == item.tx.date }

    private suspend fun restore(item: ScheduledTxWithAccount, previous: ScheduleException?) {
        if (previous != null) scheduleRepo.upsertException(previous)
        else scheduleRepo.removeException(item.tx.scheduleId, item.tx.date)
    }
}

/** True when the item is a scheduled occurrence (as opposed to an ad-hoc posting). */
val ScheduledTxWithAccount.isScheduledOccurrence: Boolean
    get() = postingId?.startsWith("${tx.scheduleId}_") ?: true

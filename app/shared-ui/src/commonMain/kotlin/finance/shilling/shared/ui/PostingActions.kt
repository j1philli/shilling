package finance.shilling.shared.ui

import finance.shilling.shared.data.ScheduleException
import finance.shilling.shared.data.ScheduledTxWithAccount
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ScheduleRepository

/**
 * Occurrence/posting actions shared by Plan and transaction detail, each paired
 * with an undo so the UI can offer it from a snackbar.
 */
class OccurrenceActions(
    private val postingRepo: PostingRepository,
    private val scheduleRepo: ScheduleRepository,
    private val snackbar: SnackbarController
) {
    suspend fun markPosted(item: ScheduledTxWithAccount) {
        postingRepo.recordFromOccurrence(item.tx)
        val base = "${item.tx.scheduleId}_${item.tx.date}"
        snackbar.showUndo("${item.tx.title} marked ${item.tx.type.postedLabel.lowercase()}") {
            deletePostingWithPartner(base)
            deletePostingWithPartner("${base}_dr")
        }
    }

    suspend fun unmarkPosted(item: ScheduledTxWithAccount) {
        val postingId = item.postingId ?: return
        deletePostingWithPartner(postingId)
        snackbar.showUndo("${item.tx.title} marked not ${item.tx.type.postedLabel.lowercase()}") {
            postingRepo.recordFromOccurrence(item.tx.copy(pairId = null))
        }
    }

    suspend fun skip(item: ScheduledTxWithAccount) {
        val previous = existingException(item)
        scheduleRepo.upsertException(
            ScheduleException(scheduleId = item.tx.scheduleId, date = item.tx.date, skip = true)
        )
        snackbar.showUndo("${item.tx.title} skipped for ${formatDate(item.tx.date)}") { restore(item, previous) }
    }

    suspend fun changeAmount(item: ScheduledTxWithAccount, amount: Double) {
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
        snackbar.showUndo("${item.tx.title} changed to ${formatCurrency(amount)} for ${formatDate(item.tx.date)}") {
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

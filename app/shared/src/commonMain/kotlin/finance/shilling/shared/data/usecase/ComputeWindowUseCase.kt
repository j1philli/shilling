package finance.shilling.shared.data.usecase

import finance.shilling.shared.data.*
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ChangeNotifier
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ScheduleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.*
import kotlin.time.Clock

class ComputeWindowUseCase(
    private val notifier: ChangeNotifier,
    private val accountRepository: AccountRepository,
    private val categoryRepository: CategoryRepository,
    private val scheduleRepository: ScheduleRepository,
    private val postingRepository: PostingRepository
) {
    fun computeFridayWindow(anchor: LocalDate): Pair<LocalDate, LocalDate> {
        val daysUntilFriday = (DayOfWeek.FRIDAY.ordinal - anchor.dayOfWeek.ordinal + 7) % 7
        val firstFriday = anchor.plus(daysUntilFriday, DateTimeUnit.DAY)
        val nextFriday = firstFriday.plus(7, DateTimeUnit.DAY)
        return firstFriday to nextFriday
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun watchWindow(start: LocalDate, end: LocalDate): Flow<List<ScheduledTxWithAccount>> =
        notifier.version.flatMapLatest {
            flow { emit(loadWindow(start, end)) }
        }

    fun watchWindowForComingFriday(): Flow<List<ScheduledTxWithAccount>> {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val window = computeFridayWindow(today)
        return watchWindow(window.first, window.second)
    }

    suspend fun balanceAt(date: LocalDate, accountId: String? = null): Double {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val accounts = loadAccounts()
        if (date <= today) {
            return if (accountId != null) accounts[accountId]?.balance ?: 0.0
            else accounts.values.sumOf { it.balance }
        }
        var balance = if (accountId != null) accounts[accountId]?.balance ?: 0.0
        else accounts.values.sumOf { it.balance }
        val window = loadWindow(today, date.plus(1, DateTimeUnit.DAY))
        window
            .filter { it.tx.affectsAccount(accountId) }
            .forEach { item ->
                balance += item.tx.deltaForAccount(accountId)
            }
        return balance
    }

    suspend fun projectCashCurve(accountId: String?, from: LocalDate, to: LocalDate): List<CashPoint> {
        val accounts = loadAccounts()
        var running = if (accountId != null) accounts[accountId]?.balance ?: 0.0
        else accounts.values.sumOf { it.balance }
        val changesByDate = loadWindow(from, to)
            .filter { it.tx.affectsAccount(accountId) }
            .groupBy { it.tx.date }
            .mapValues { entry ->
                entry.value.sumOf { item -> item.tx.deltaForAccount(accountId) }
            }

        val points = mutableListOf<CashPoint>()
        var cursor = from
        while (cursor < to) {
            running += changesByDate[cursor] ?: 0.0
            points += CashPoint(cursor, running)
            cursor = cursor.plus(1, DateTimeUnit.DAY)
        }
        return points
    }

    // --- Internal ---

    private suspend fun loadAccounts(): Map<String?, Account> =
        accountRepository.getAll()

    private suspend fun loadWindow(start: LocalDate, end: LocalDate): List<ScheduledTxWithAccount> {
        val accounts = loadAccounts()
        val categories = categoryRepository.getAll().associateBy { it.id }
        val schedules = scheduleRepository.getIntersecting(start, end)
        if (schedules.isEmpty()) return emptyList()

        val scheduleIds = schedules.map { it.id }
        val exceptions = scheduleRepository.getExceptions(scheduleIds)
        val postings = postingRepository.getBetween(start, end)
        val postedDatesBySchedule: Map<String, Set<LocalDate>> = postings
            .filter { it.scheduleId != null }
            .groupBy { it.scheduleId!! }
            .mapValues { entry -> entry.value.map { it.date }.toSet() }

        val scheduleMap = schedules.associateBy { it.id }
        val (transferPostings, standardPostings) = postings.partition { posting ->
            posting.scheduleId?.let { scheduleMap[it]?.type == ScheduleType.TRANSFER } == true
        }

        val projected = schedules.flatMap { schedule ->
            val ex = exceptions[schedule.id]?.associateBy { it.date } ?: emptyMap()
            val postedDates = postedDatesBySchedule[schedule.id] ?: emptySet()
            generateOccurrences(schedule, start, end, ex, postedDates)
                .map { tx ->
                    ScheduledTxWithAccount(
                        tx = tx,
                        account = accounts[tx.accountId.ifBlank { null }],
                        counterAccount = accounts[tx.counterAccountId?.takeIf { it.isNotBlank() }],
                        category = tx.categoryId?.let { categories[it] },
                        posted = false
                    )
                }
        }

        val postedStandard = standardPostings.map { posting ->
            val schedule = posting.scheduleId?.let { scheduleMap[it] }
            val title = schedule?.title ?: "Posting"
            val counterAccountId = when {
                schedule?.type == ScheduleType.TRANSFER && schedule.counterAccountId != null -> {
                    if (posting.accountId == schedule.accountId) schedule.counterAccountId
                    else schedule.accountId
                }
                else -> schedule?.counterAccountId
            }
            val tx = ScheduledTx(
                scheduleId = posting.scheduleId ?: posting.id,
                title = title,
                date = posting.date,
                amount = posting.amount,
                type = posting.type,
                accountId = posting.accountId,
                counterAccountId = counterAccountId,
                categoryId = schedule?.categoryId,
                autoPay = schedule?.autoPay == true,
                pairId = posting.pairId
            )
            ScheduledTxWithAccount(
                tx = tx,
                account = accounts[posting.accountId],
                counterAccount = counterAccountId?.let { accounts[it] },
                category = tx.categoryId?.let { categories[it] },
                posted = true
            )
        }
        val postedTransfers = combineTransferPostings(transferPostings, scheduleMap, accounts, categories)

        return (projected + postedStandard + postedTransfers).sortedBy { it.tx.date }
    }

    private fun combineTransferPostings(
        postings: List<Posting>,
        schedules: Map<String, Schedule>,
        accounts: Map<String?, Account>,
        categories: Map<String, Category>
    ): List<ScheduledTxWithAccount> {
        if (postings.isEmpty()) return emptyList()
        return postings.groupBy { posting ->
            posting.pairId ?: "${posting.scheduleId ?: "posting"}_${posting.id}"
        }.map { (pairId, entries) ->
            val schedule = entries.firstNotNullOfOrNull { it.scheduleId?.let(schedules::get) }
            val date = entries.first().date
            val debit = entries.firstOrNull { it.type == ScheduleType.EXPENSE }
            val credit = entries.firstOrNull { it.type == ScheduleType.INCOME }
            val amount = debit?.amount ?: credit?.amount ?: schedule?.amount ?: 0.0
            val sourceAccountId = debit?.accountId ?: schedule?.accountId.orEmpty()
            val destinationAccountId = credit?.accountId ?: schedule?.counterAccountId
            val tx = ScheduledTx(
                scheduleId = schedule?.id ?: pairId,
                title = schedule?.title ?: "Transfer",
                date = date,
                amount = amount,
                type = ScheduleType.TRANSFER,
                accountId = sourceAccountId,
                counterAccountId = destinationAccountId,
                categoryId = schedule?.categoryId,
                autoPay = schedule?.autoPay == true,
                pairId = pairId
            )
            ScheduledTxWithAccount(
                tx = tx,
                account = accounts[sourceAccountId.takeIf { it.isNotBlank() }],
                counterAccount = destinationAccountId?.takeIf { it.isNotBlank() }?.let { accounts[it] },
                category = tx.categoryId?.let { categories[it] },
                posted = true
            )
        }
    }
}

private fun ScheduledTx.affectsAccount(target: String?): Boolean {
    if (target == null) return true
    if (accountId == target) return true
    if (type == ScheduleType.TRANSFER && counterAccountId == target) return true
    return false
}

private fun ScheduledTx.deltaForAccount(target: String?): Double = when (type) {
    ScheduleType.INCOME -> if (target == null || target == accountId) amount else 0.0
    ScheduleType.EXPENSE -> if (target == null || target == accountId) -amount else 0.0
    ScheduleType.TRANSFER -> when {
        target == null -> 0.0
        target == accountId -> -amount
        target == counterAccountId -> amount
        else -> 0.0
    }
}

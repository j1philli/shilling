package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.generateOccurrences
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ScheduleRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** A picker option (account, category, …). */
data class Choice(val id: String, val label: String)

/** Weekday choice; [index] is the ISO ordinal (Monday = 0), used for day masks. */
data class WeekdayChoice(val index: Int, val shortLabel: String, val fullLabel: String)

data class FrequencyChoice(val frequency: Frequency, val label: String)

/** Editable schedule fields. Dates are epoch days so every UI can bind them. */
data class ScheduleFields(
    val type: ScheduleType = ScheduleType.EXPENSE,
    val title: String = "",
    val amountText: String = "",
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null,
    val frequency: Frequency = Frequency.MONTHLY_BY_DAY,
    val intervalText: String = "1",
    val startEpochDay: Long = today().toEpochDays(),
    val endEpochDay: Long? = null,
    val weekdayMask: Int = 0,
    val monthDayText: String = today().day.toString(),
    val lastDay: Boolean = false,
    val nth: Int = today().weekOfMonth(),
    val nthWeekdayIndex: Int = today().dayOfWeek.ordinal,
    val autoPay: Boolean = false,
    val notes: String = ""
)

data class ScheduleEditorUiState(
    val load: EditorLoad = EditorLoad.LOADING,
    val isNew: Boolean = false,
    val title: String = "Schedule",
    val fields: ScheduleFields = ScheduleFields(),
    val accounts: List<Choice> = emptyList(),
    val categories: List<Choice> = emptyList(),
    val deleteConfirm: ConfirmCopy? = null,
    // Derived
    val isTransfer: Boolean = false,
    val namePlaceholder: String = "",
    val accountLabel: String = "Account",
    val accountHint: String? = null,
    val toAccountChoices: List<Choice> = emptyList(),
    val toAccountHint: String? = null,
    val startLabel: String = "Starts on",
    /** "weeks" etc. when the frequency takes an "every N" interval. */
    val intervalUnit: String? = null,
    /** Weekday mask with the start day filled in when empty (Weekly). */
    val effectiveWeekdayMask: Int = 0,
    val monthDayError: Boolean = false,
    val showsEndDate: Boolean = true,
    val nextOccurrence: String = "",
    val errors: List<String> = emptyList(),
    val saveEnabled: Boolean = false
) {
    val missingMessage: String get() = "This schedule was deleted."
    val frequencies: List<FrequencyChoice> get() = Frequency.entries.map { FrequencyChoice(it, it.label) }
    val types: List<ScheduleType> get() = ScheduleType.entries
    val weekdays: List<WeekdayChoice> get() = DayOfWeek.entries.map { WeekdayChoice(it.ordinal, it.shortLabel, it.fullLabel) }
    val nthOptions: List<Choice> get() = NTH_LABELS.mapIndexed { i, label -> Choice((i + 1).toString(), label) }
    val monthDayHint: String get() = "Months without this day are skipped. Use \"Last day\" for month-end bills."
    val autoPayHint: String get() = "Labels it in Plan as paid automatically by your bank."
}

private val NTH_LABELS = listOf("First", "Second", "Third", "Fourth", "Fifth")

class ScheduleEditorViewModel(
    private val scheduleId: String?,
    presetType: ScheduleType?,
    private val scheduleRepository: ScheduleRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    private val idGenerator: IdGenerator
) : ViewModel() {
    private val form = EditorForm(
        ScheduleFields(type = presetType ?: ScheduleType.EXPENSE),
        if (scheduleId == null) EditorLoad.READY else EditorLoad.LOADING
    )
    private var existing: Schedule? = null

    val state: StateFlow<ScheduleEditorUiState> = combine(
        form.state, accountRepository.watchAll(), categoryRepository.watchAll()
    ) { (f, l), accounts, categories ->
        // Default (or repair) the source account once accounts are known.
        val accountId = f.accountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        val fields = if (accountId != f.accountId) {
            // Repair the current value, not this snapshot: the item may have loaded since `f`
            // was emitted, and writing `f.copy(…)` back would wipe the loaded fields.
            form.update { current -> if (current.accountId == f.accountId) current.copy(accountId = accountId) else current }
            f.copy(accountId = accountId)
        } else {
            f
        }
        build(fields, l, accounts.map { Choice(it.id, it.name) }, categories.map { Choice(it.id, it.name) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ScheduleEditorUiState(load = form.load))

    init {
        if (scheduleId != null) {
            viewModelScope.launch {
                val schedule = scheduleRepository.watchAll().first().firstOrNull { it.id == scheduleId }
                existing = schedule
                if (schedule == null) {
                    form.missing()
                } else {
                    form.loaded(
                        ScheduleFields(
                            type = schedule.type,
                            title = schedule.title,
                            amountText = formatAmountInput(schedule.amount),
                            accountId = schedule.accountId.takeIf { it.isNotBlank() },
                            toAccountId = schedule.counterAccountId,
                            categoryId = schedule.categoryId,
                            frequency = schedule.freq,
                            intervalText = schedule.interval.toString(),
                            startEpochDay = schedule.startDate.toEpochDays(),
                            endEpochDay = schedule.endDate?.toEpochDays(),
                            weekdayMask = schedule.byDayMask ?: 0,
                            monthDayText = (schedule.byMonthDay ?: schedule.startDate.day).toString(),
                            lastDay = schedule.lastDayFlag,
                            nth = schedule.nthWeekday ?: schedule.startDate.weekOfMonth(),
                            nthWeekdayIndex = schedule.firstWeekdayFromMaskFallback().ordinal,
                            autoPay = schedule.autoPay,
                            notes = schedule.notes.orEmpty()
                        )
                    )
                    // Deleted elsewhere (e.g. on another device) while open: show it as deleted, since saving
                    // would bring it back.
                    scheduleRepository.watchAll().first { list -> list.none { it.id == scheduleId } }
                    form.missing()
                }
            }
        }
    }

    fun update(transform: (ScheduleFields) -> ScheduleFields) = form.update(transform)

    // Swift-friendly setters (Kotlin lambdas with data-class copies are awkward from Swift).
    fun setType(value: ScheduleType) = update { it.copy(type = value) }
    fun setTitle(value: String) = update { it.copy(title = value) }
    fun setAmountText(value: String) = update { it.copy(amountText = value) }
    fun setAccount(id: String?) = update { it.copy(accountId = id) }
    fun setToAccount(id: String?) = update { it.copy(toAccountId = id) }
    fun setCategory(id: String?) = update { it.copy(categoryId = id) }
    fun setFrequency(value: Frequency) = update { it.copy(frequency = value) }
    fun setIntervalText(value: String) = update { it.copy(intervalText = value) }
    fun setStart(epochDay: Long) = update { it.copy(startEpochDay = epochDay) }
    fun setEnd(epochDay: Long?) = update { it.copy(endEpochDay = epochDay) }
    fun setMonthDayText(value: String) = update { it.copy(monthDayText = value) }
    fun setLastDay(value: Boolean) = update { it.copy(lastDay = value) }
    fun setNth(value: Int) = update { it.copy(nth = value) }
    fun setNthWeekday(index: Int) = update { it.copy(nthWeekdayIndex = index) }
    fun setAutoPay(value: Boolean) = update { it.copy(autoPay = value) }
    fun setNotes(value: String) = update { it.copy(notes = value) }

    /** Toggles a weekday (Weekly); the last selected day can't be turned off. */
    fun toggleWeekday(index: Int) {
        val mask = state.value.effectiveWeekdayMask
        val next = mask xor (1 shl index)
        if (next != 0) update { it.copy(weekdayMask = next) }
    }

    suspend fun save(): String? {
        if (!state.value.saveEnabled) return null
        scheduleRepository.upsert(draft(form.fields).copy(id = existing?.id ?: idGenerator.newId()))
        return if (existing == null) "Schedule added" else "Schedule updated"
    }

    suspend fun delete(): String? {
        val schedule = existing ?: return null
        scheduleRepository.delete(schedule.id)
        return "${schedule.title} deleted"
    }

    private fun build(f: ScheduleFields, l: EditorLoad, accounts: List<Choice>, categories: List<Choice>): ScheduleEditorUiState {
        val startDate = LocalDate.fromEpochDays(f.startEpochDay)
        val endDate = f.endEpochDay?.let { LocalDate.fromEpochDays(it) }
        val amount = parseAmountInput(f.amountText)
        val interval = f.intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val monthDay = f.monthDayText.toIntOrNull()
        val isTransfer = f.type == ScheduleType.TRANSFER
        val errors = buildList {
            if (isTransfer && (f.toAccountId == null || f.toAccountId == f.accountId)) add("Choose a different destination account")
            if (f.frequency == Frequency.MONTHLY_BY_DAY && !f.lastDay && (monthDay == null || monthDay !in 1..31)) add("Day of month must be 1–31")
            if (endDate != null && endDate < startDate) add("End date is before the start date")
        }
        val draft = draft(f)
        val from = maxOf(today(), draft.startDate)
        val next = generateOccurrences(draft, from, from.plus(3, DateTimeUnit.YEAR)).firstOrNull()?.date
        val schedule = existing
        return ScheduleEditorUiState(
            load = l,
            isNew = schedule == null,
            title = schedule?.title ?: if (scheduleId == null) "New schedule" else "Schedule",
            fields = f,
            accounts = accounts,
            categories = categories,
            deleteConfirm = schedule?.let {
                ConfirmCopy(
                    title = "Delete ${it.title}?",
                    message = "Transactions already recorded from this schedule are deleted too. This can't be undone.",
                    confirmLabel = "Delete schedule",
                    destructive = true
                )
            },
            isTransfer = isTransfer,
            namePlaceholder = when (f.type) {
                ScheduleType.EXPENSE -> "e.g. Rent"
                ScheduleType.INCOME -> "e.g. Paycheck"
                ScheduleType.TRANSFER -> "e.g. Savings"
            },
            accountLabel = if (isTransfer) "From account" else "Account",
            accountHint = if (accounts.isEmpty()) "Add an account first" else null,
            toAccountChoices = accounts.filter { it.id != f.accountId },
            toAccountHint = if (accounts.size < 2) "Transfers need at least two accounts" else null,
            startLabel = if (f.frequency == Frequency.ONCE) "Date" else "Starts on",
            intervalUnit = f.frequency.intervalUnit(interval),
            effectiveWeekdayMask = f.weekdayMask.takeIf { it != 0 } ?: (1 shl startDate.dayOfWeek.ordinal),
            monthDayError = monthDay == null || monthDay !in 1..31,
            showsEndDate = f.frequency != Frequency.ONCE,
            nextOccurrence = next?.let { "Next: ${formatDate(it)}" } ?: "No upcoming dates",
            errors = errors,
            saveEnabled = l == EditorLoad.READY && f.title.isNotBlank() && amount != null && amount > 0 &&
                f.accountId != null && errors.isEmpty()
        )
    }

    private fun draft(f: ScheduleFields): Schedule {
        val startDate = LocalDate.fromEpochDays(f.startEpochDay)
        val interval = f.intervalText.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val monthDay = f.monthDayText.toIntOrNull()
        val isTransfer = f.type == ScheduleType.TRANSFER
        return Schedule(
            id = existing?.id ?: "draft",
            title = f.title.trim(),
            amount = parseAmountInput(f.amountText) ?: 0.0,
            type = f.type,
            accountId = f.accountId.orEmpty(),
            counterAccountId = if (isTransfer) f.toAccountId else null,
            categoryId = f.categoryId,
            startDate = startDate,
            endDate = if (f.frequency == Frequency.ONCE) null else f.endEpochDay?.let { LocalDate.fromEpochDays(it) },
            freq = f.frequency,
            interval = if (f.frequency.intervalUnit(interval) != null) interval else 1,
            byDayMask = when (f.frequency) {
                Frequency.WEEKLY -> f.weekdayMask.takeIf { it != 0 } ?: (1 shl startDate.dayOfWeek.ordinal)
                Frequency.MONTHLY_BY_NTH_WEEKDAY -> 1 shl f.nthWeekdayIndex
                else -> null
            },
            byMonthDay = when (f.frequency) {
                Frequency.MONTHLY_BY_DAY -> monthDay ?: startDate.day
                Frequency.YEARLY -> startDate.day
                else -> null
            },
            nthWeekday = if (f.frequency == Frequency.MONTHLY_BY_NTH_WEEKDAY) f.nth else null,
            lastDayFlag = f.frequency == Frequency.MONTHLY_BY_DAY && f.lastDay,
            autoPay = f.autoPay,
            notes = f.notes.trim().ifBlank { null }
        )
    }
}

package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.Schedule
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** A list's empty state (with an "Add …" action). */
data class ListEmpty(val title: String, val message: String, val actionLabel: String)

// ─── Schedules ───────────────────────────────────────────────────────────────

data class ScheduleRowUi(
    val id: String,
    val title: String,
    val supporting: String,
    val type: ScheduleType,
    val amount: String,
    val categoryColor: String?
)

/** Rows under a type header ([header] is null when a type filter is active). */
data class ScheduleGroupUi(val header: String?, val rows: List<ScheduleRowUi>)

data class SchedulesUiState(
    /** Null = all types. */
    val filter: ScheduleType? = null,
    val filterOptions: List<ScheduleType> = ScheduleType.entries,
    val groups: List<ScheduleGroupUi> = emptyList(),
    val empty: ListEmpty? = null
)

class SchedulesViewModel(
    scheduleRepository: ScheduleRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository
) : ViewModel() {
    private val filter = MutableStateFlow<ScheduleType?>(null)
    private val today = today()

    val state: StateFlow<SchedulesUiState> = combine(
        scheduleRepository.watchAll(),
        accountRepository.watchAll(),
        categoryRepository.watchAll(),
        filter
    ) { schedules, accounts, categories, selected ->
        val accountsById = accounts.associateBy { it.id }
        val categoriesById = categories.associateBy { it.id }
        val visible = schedules.filter { selected == null || it.type == selected }
            .sortedWith(compareBy<Schedule> { it.type.ordinal }.thenBy { it.title.lowercase() })
        SchedulesUiState(
            filter = selected,
            groups = visible.groupBy { it.type }.map { (type, group) ->
                ScheduleGroupUi(
                    header = if (selected == null) type.pluralLabel else null,
                    rows = group.map { schedule ->
                        val source = accountsById[schedule.accountId]?.name ?: "No account"
                        val category = categoriesById[schedule.categoryId]
                        val ended = schedule.endDate?.let { it < today } == true
                        ScheduleRowUi(
                            id = schedule.id,
                            title = schedule.title,
                            supporting = buildList {
                                add(if (ended) "Ended ${formatDate(schedule.endDate!!, today)}" else describeRecurrence(schedule))
                                add(
                                    if (schedule.type == ScheduleType.TRANSFER) {
                                        "$source → ${accountsById[schedule.counterAccountId]?.name ?: "No account"}"
                                    } else source
                                )
                                category?.let { add(it.name) }
                            }.joinToString(" · "),
                            type = schedule.type,
                            amount = formatSigned(schedule.type, schedule.amount),
                            categoryColor = category?.color
                        )
                    }
                )
            },
            empty = if (visible.isNotEmpty()) null else ListEmpty(
                title = if (selected == null) "No schedules yet" else "No ${selected.pluralLabel.lowercase()} scheduled",
                message = "Schedules are the bills, paychecks and transfers you expect. They drive Plan.",
                actionLabel = "Add schedule"
            )
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), SchedulesUiState())

    fun setFilter(type: ScheduleType?) {
        filter.value = type
    }
}

// ─── Categories ──────────────────────────────────────────────────────────────

data class CategoryRowUi(val id: String, val name: String, val color: String?)

data class CategoriesUiState(val rows: List<CategoryRowUi> = emptyList(), val empty: ListEmpty? = null)

class CategoriesViewModel(categoryRepository: CategoryRepository) : ViewModel() {
    val state: StateFlow<CategoriesUiState> = categoryRepository.watchAll().map { categories ->
        CategoriesUiState(
            rows = categories.sortedBy { it.name.lowercase() }.map { CategoryRowUi(it.id, it.name, it.color) },
            empty = if (categories.isNotEmpty()) null else ListEmpty(
                "No categories yet",
                "Categories group schedules and transactions in Plan.",
                "Add category"
            )
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), CategoriesUiState())
}

// ─── Accounts ────────────────────────────────────────────────────────────────

data class AccountRowUi(val id: String, val name: String, val balance: String)

data class AccountsUiState(
    /** "Total $1,234.00", or null without accounts. */
    val subtitle: String? = null,
    val rows: List<AccountRowUi> = emptyList(),
    val empty: ListEmpty? = null
)

class AccountsViewModel(accountRepository: AccountRepository) : ViewModel() {
    val state: StateFlow<AccountsUiState> = accountRepository.watchAll().map { accounts ->
        AccountsUiState(
            subtitle = if (accounts.isEmpty()) null else "Total ${formatCurrency(accounts.sumOf { it.balance })}",
            rows = accounts.sortedBy { it.name.lowercase() }.map { AccountRowUi(it.id, it.name, formatCurrency(it.balance)) },
            empty = if (accounts.isNotEmpty()) null else ListEmpty(
                "No accounts yet",
                "Add the bank accounts and cash you budget with.",
                "Add account"
            )
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), AccountsUiState())
}

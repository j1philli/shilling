package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How an editor for an existing item is doing: still loading, or the item was deleted. */
enum class EditorLoad { READY, LOADING, MISSING }

/** An editor's fields together with its [EditorLoad]. */
internal data class FormState<F>(val fields: F, val load: EditorLoad)

/**
 * An editor's form fields and load state in one flow. Loading sets both at once, so a UI never
 * sees READY with the fields still at their defaults (iOS copies text fields once, on READY).
 */
internal class EditorForm<F>(initial: F, load: EditorLoad) {
    private val flow = MutableStateFlow(FormState(initial, load))
    val state: StateFlow<FormState<F>> get() = flow
    val fields: F get() = flow.value.fields
    val load: EditorLoad get() = flow.value.load

    fun update(transform: (F) -> F) = flow.update { it.copy(fields = transform(it.fields)) }

    fun loaded(fields: F) {
        flow.value = FormState(fields, EditorLoad.READY)
    }

    fun missing() = flow.update { it.copy(load = EditorLoad.MISSING) }
}

// ─── Category ────────────────────────────────────────────────────────────────

data class Swatch(val hex: String, val name: String)

val categorySwatches = listOf(
    Swatch("#EF5350", "Red"),
    Swatch("#EC407A", "Pink"),
    Swatch("#AB47BC", "Purple"),
    Swatch("#7E57C2", "Violet"),
    Swatch("#5C6BC0", "Indigo"),
    Swatch("#42A5F5", "Blue"),
    Swatch("#26C6DA", "Cyan"),
    Swatch("#26A69A", "Teal"),
    Swatch("#66BB6A", "Green"),
    Swatch("#9CCC65", "Lime"),
    Swatch("#FFCA28", "Amber"),
    Swatch("#FFA726", "Orange"),
    Swatch("#8D6E63", "Brown"),
    Swatch("#78909C", "Slate")
)

data class CategoryEditorUiState(
    val load: EditorLoad,
    val isNew: Boolean,
    val title: String,
    val name: String = "",
    val color: String = categorySwatches.first().hex,
    /** Presets, plus the current color when it's custom. */
    val swatches: List<Swatch> = categorySwatches,
    /** Delete confirmation; null for new items. */
    val deleteConfirm: ConfirmCopy? = null
) {
    val saveEnabled: Boolean get() = load == EditorLoad.READY && name.isNotBlank()
    val missingMessage: String get() = "This category was deleted."
}

class CategoryEditorViewModel(
    private val categoryId: String?,
    private val categoryRepository: CategoryRepository,
    private val idGenerator: IdGenerator
) : ViewModel() {
    private val _state = MutableStateFlow(
        if (categoryId == null) {
            CategoryEditorUiState(EditorLoad.READY, isNew = true, title = "New category", color = categorySwatches.random().hex)
        } else {
            CategoryEditorUiState(EditorLoad.LOADING, isNew = false, title = "Category")
        }
    )
    val state: StateFlow<CategoryEditorUiState> = _state
    private var existing: Category? = null

    init {
        if (categoryId != null) {
            viewModelScope.launch {
                val category = categoryRepository.watchAll().first().firstOrNull { it.id == categoryId }
                existing = category
                _state.value = if (category == null) {
                    _state.value.copy(load = EditorLoad.MISSING)
                } else {
                    CategoryEditorUiState(
                        load = EditorLoad.READY,
                        isNew = false,
                        title = category.name,
                        name = category.name,
                        color = category.color ?: categorySwatches.first().hex,
                        deleteConfirm = ConfirmCopy(
                            title = "Delete ${category.name}?",
                            message = "Schedules and transactions in this category become Uncategorized.",
                            confirmLabel = "Delete category",
                            destructive = true
                        )
                    ).withSwatches()
                }
                if (category != null) {
                    // Deleted elsewhere (e.g. on another device) while open: show it as deleted, since saving
                    // would bring it back.
                    categoryRepository.watchAll().first { list -> list.none { it.id == categoryId } }
                    _state.update { it.copy(load = EditorLoad.MISSING) }
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setColor(hex: String) = _state.update { it.copy(color = hex).withSwatches() }

    /** Returns the confirmation message, or null when nothing was saved. */
    suspend fun save(): String? {
        val current = _state.value
        if (!current.saveEnabled) return null
        categoryRepository.upsert(Category(id = existing?.id ?: idGenerator.newId(), name = current.name.trim(), color = current.color))
        return if (existing == null) "Category added" else "Category updated"
    }

    suspend fun delete(): String? {
        val category = existing ?: return null
        categoryRepository.delete(category.id)
        return "${category.name} deleted"
    }

    private fun CategoryEditorUiState.withSwatches(): CategoryEditorUiState {
        val custom = color.takeIf { current -> categorySwatches.none { it.hex.equals(current, ignoreCase = true) } }
        return copy(swatches = categorySwatches + listOfNotNull(custom?.let { Swatch(it, "Custom") }))
    }
}

// ─── Account ─────────────────────────────────────────────────────────────────

data class AccountEditorUiState(
    val load: EditorLoad,
    val isNew: Boolean,
    val title: String,
    val name: String = "",
    val balanceText: String = "",
    /** Delete confirmation; null for new items. */
    val deleteConfirm: ConfirmCopy? = null
) {
    val balanceLabel: String get() = if (isNew) "Starting balance" else "Current balance"
    val balanceHint: String get() = "Balances are entered manually and aren't changed by recorded transactions."
    val balance: Double? get() = if (balanceText.isBlank()) 0.0 else parseAmountInput(balanceText)
    val saveEnabled: Boolean get() = load == EditorLoad.READY && name.isNotBlank() && balance != null
    val missingMessage: String get() = "This account was deleted."
}

class AccountEditorViewModel(
    private val accountId: String?,
    private val accountRepository: AccountRepository,
    private val idGenerator: IdGenerator
) : ViewModel() {
    private val _state = MutableStateFlow(
        if (accountId == null) AccountEditorUiState(EditorLoad.READY, isNew = true, title = "New account")
        else AccountEditorUiState(EditorLoad.LOADING, isNew = false, title = "Account")
    )
    val state: StateFlow<AccountEditorUiState> = _state
    private var existing: Account? = null

    init {
        if (accountId != null) {
            viewModelScope.launch {
                val account = accountRepository.watchAll().first().firstOrNull { it.id == accountId }
                existing = account
                _state.value = if (account == null) {
                    _state.value.copy(load = EditorLoad.MISSING)
                } else {
                    AccountEditorUiState(
                        load = EditorLoad.READY,
                        isNew = false,
                        title = account.name,
                        name = account.name,
                        balanceText = formatAmountInput(account.balance).let { if (account.balance < 0) "-$it" else it },
                        deleteConfirm = ConfirmCopy(
                            title = "Delete ${account.name}?",
                            message = "All transactions recorded to this account will be deleted, and schedules that use it " +
                                "will need a new account. This can't be undone.",
                            confirmLabel = "Delete account",
                            destructive = true
                        )
                    )
                }
                if (account != null) {
                    // Deleted elsewhere (e.g. on another device) while open: show it as deleted, since saving
                    // would bring it back.
                    accountRepository.watchAll().first { list -> list.none { it.id == accountId } }
                    _state.update { it.copy(load = EditorLoad.MISSING) }
                }
            }
        }
    }

    fun setName(value: String) = _state.update { it.copy(name = value) }

    fun setBalanceText(value: String) = _state.update { it.copy(balanceText = value) }

    suspend fun save(): String? {
        val current = _state.value
        val balance = current.balance ?: return null
        if (!current.saveEnabled) return null
        accountRepository.upsert(Account(id = existing?.id ?: idGenerator.newId(), name = current.name.trim(), balance = balance))
        return if (existing == null) "Account added" else "Account updated"
    }

    suspend fun delete(): String? {
        val account = existing ?: return null
        accountRepository.delete(account.id)
        return "${account.name} deleted"
    }
}

package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.Posting
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.analytics.ProductAnalytics
import finance.shilling.shared.data.analytics.ProductEvent
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.time.Clock

data class TransactionFields(
    val type: ScheduleType = ScheduleType.EXPENSE,
    val title: String = "",
    val amountText: String = "",
    val dateEpochDay: Long = today().toEpochDays(),
    val accountId: String? = null,
    val toAccountId: String? = null,
    val categoryId: String? = null
)

data class AttachedReceiptUi(val receipt: Receipt, val addedLabel: String) {
    val id: String get() = receipt.id
    val name: String get() = receipt.originalName
}

data class TransactionEditorUiState(
    val load: EditorLoad = EditorLoad.LOADING,
    val isNew: Boolean = true,
    val title: String = "Transaction",
    /** "Recorded from a schedule" / "One-off expense". */
    val subtitle: String? = null,
    val fields: TransactionFields = TransactionFields(),
    val accounts: List<Choice> = emptyList(),
    val categories: List<Choice> = emptyList(),
    /** Only new transactions can change type. */
    val typeEditable: Boolean = true,
    val isTransfer: Boolean = false,
    val accountLabel: String = "Account",
    val accountHint: String? = null,
    val toAccountChoices: List<Choice> = emptyList(),
    val toAccountHint: String? = null,
    val categoryNoneLabel: String = "Uncategorized",
    val dateHint: String? = null,
    val deleteConfirm: ConfirmCopy? = null,
    /** Receipts section (existing transactions only). */
    val showsReceipts: Boolean = false,
    val receipts: List<AttachedReceiptUi> = emptyList(),
    val saveEnabled: Boolean = false
) {
    val missingMessage: String get() = "This transaction was deleted."
    val types: List<ScheduleType> get() = ScheduleType.entries
}

@OptIn(ExperimentalCoroutinesApi::class)
class TransactionEditorViewModel(
    private val postingId: String?,
    private val postingRepository: PostingRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    private val receiptRepository: ReceiptRepository,
    private val fileStore: ReceiptFileStore,
    private val idGenerator: IdGenerator,
    private val analytics: ProductAnalytics
) : ViewModel() {
    private val form = EditorForm(TransactionFields(), if (postingId == null) EditorLoad.READY else EditorLoad.LOADING)
    private var existing: PostingWithDetails? = null
    private var debit: Posting? = null
    private var credit: Posting? = null
    private var partner: Posting? = null
    private val debitId = MutableStateFlow<String?>(null)

    private val receipts = debitId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else receiptRepository.watchByPosting(id)
    }

    val state: StateFlow<TransactionEditorUiState> = combine(
        form.state, accountRepository.watchAll(), categoryRepository.watchAll(), receipts
    ) { (f, l), accounts, categories, attached ->
        val accountId = f.accountId?.takeIf { id -> accounts.any { it.id == id } } ?: accounts.firstOrNull()?.id
        val fields = if (accountId != f.accountId) {
            // Repair the current value, not this snapshot: the item may have loaded since `f`
            // was emitted, and writing `f.copy(…)` back would wipe the loaded fields.
            form.update { current -> if (current.accountId == f.accountId) current.copy(accountId = accountId) else current }
            f.copy(accountId = accountId)
        } else {
            f
        }
        build(fields, l, accounts.map { Choice(it.id, it.name) }, categories.map { Choice(it.id, it.name) }, attached)
    }.stateIn(viewModelScope, SharingStarted.Eagerly,
        TransactionEditorUiState(load = form.load, isNew = postingId == null, fields = form.fields))

    init {
        if (postingId != null) {
            viewModelScope.launch {
                val details = postingRepository.watchById(postingId).first()
                if (details == null) {
                    form.missing()
                    return@launch
                }
                val posting = details.posting
                val other = postingRepository.getTransferPartner(posting)
                existing = details
                partner = other
                // For transfers, edit from the debit (outgoing) leg's point of view.
                debit = if (other == null || posting.type == ScheduleType.EXPENSE) posting else other
                credit = if (other == null) null else if (debit === posting) other else posting
                debitId.value = debit!!.id
                form.loaded(
                    TransactionFields(
                        type = if (other != null) ScheduleType.TRANSFER else posting.type,
                        title = details.title,
                        amountText = formatAmountInput(posting.amount),
                        dateEpochDay = posting.date.toEpochDays(),
                        accountId = debit!!.accountId,
                        toAccountId = credit?.accountId,
                        categoryId = posting.categoryId
                    )
                )
                // Deleted elsewhere (e.g. on another device) while open: show it as deleted, since saving
                // would bring it back.
                postingRepository.watchById(postingId).first { it == null }
                form.missing()
            }
        }
    }

    fun setType(value: ScheduleType) = form.update { it.copy(type = value) }
    fun setTitle(value: String) = form.update { it.copy(title = value) }
    fun setAmountText(value: String) = form.update { it.copy(amountText = value) }
    fun setDate(epochDay: Long) = form.update { it.copy(dateEpochDay = epochDay) }
    fun setAccount(id: String?) = form.update { it.copy(accountId = id) }
    fun setToAccount(id: String?) = form.update { it.copy(toAccountId = id) }
    fun setCategory(id: String?) = form.update { it.copy(categoryId = id) }

    suspend fun save(): String? {
        if (form.load != EditorLoad.READY || !canSave(form.fields)) return null
        val f = form.fields
        val amount = parseAmountInput(f.amountText) ?: return null
        val from = f.accountId ?: return null
        val date = LocalDate.fromEpochDays(f.dateEpochDay)
        val details = existing
        if (details == null) {
            if (f.type == ScheduleType.TRANSFER) {
                postingRepository.recordAdHocTransfer(f.title.trim(), amount, from, f.toAccountId!!, f.categoryId, date)
            } else {
                postingRepository.recordAdHoc(f.title.trim(), amount, f.type, from, f.categoryId, date)
            }
            analytics.captureAsync(ProductEvent.POSTING_CREATED)
            return "Transaction added"
        }
        val posting = details.posting
        // Keep a scheduled posting's title linked to its schedule unless it was edited.
        val newTitle = f.title.trim().takeUnless { posting.scheduleId != null && it == details.title } ?: posting.title
        postingRepository.record(
            debit!!.copy(title = newTitle, amount = amount, date = date, accountId = from, categoryId = f.categoryId)
        )
        credit?.let {
            postingRepository.record(
                it.copy(title = newTitle, amount = amount, date = date, accountId = f.toAccountId ?: it.accountId, categoryId = f.categoryId)
            )
        }
        return "Transaction updated"
    }

    suspend fun delete(): Undoable? {
        val posting = existing?.posting ?: return null
        val removed = listOfNotNull(posting, partner)
        removed.forEach { postingRepository.delete(it.id) }
        return Undoable("Transaction deleted") { removed.forEach { postingRepository.record(it) } }
    }

    /** Stores [bytes] as a new receipt attached to this transaction. */
    suspend fun attachReceipt(fileName: String, bytes: ByteArray): String? {
        val postingId = debitId.value ?: return null
        val receiptId = idGenerator.newId()
        receiptRepository.saveWithFile(
            Receipt(
                id = receiptId,
                postingId = postingId,
                filePath = fileName,
                originalName = fileName,
                addedAt = Clock.System.now().toEpochMilliseconds()
            ), fileStore, bytes
        )
        analytics.captureAsync(ProductEvent.RECEIPT_ATTACHED)
        return "Receipt attached"
    }

    suspend fun detachReceipt(receiptId: String): Undoable? {
        val postingId = debitId.value ?: return null
        receiptRepository.detach(receiptId)
        return Undoable("Receipt detached") { receiptRepository.attach(receiptId, postingId) }
    }

    private fun build(
        f: TransactionFields,
        l: EditorLoad,
        accounts: List<Choice>,
        categories: List<Choice>,
        attached: List<Receipt>
    ): TransactionEditorUiState {
        val details = existing
        val isScheduled = details?.posting?.scheduleId != null
        val linkedSpaceTransfer = details?.posting?.pairId?.startsWith(finance.shilling.shared.data.store.SPACE_TRANSFER_PREFIX) == true
        val isTransfer = f.type == ScheduleType.TRANSFER
        return TransactionEditorUiState(
            load = l,
            isNew = details == null,
            title = details?.title ?: if (postingId == null) "New transaction" else "Transaction",
            subtitle = details?.let { if (linkedSpaceTransfer) "Linked space transfer. Edit both sides in Settings → Finance spaces." else if (isScheduled) "Recorded from a schedule" else "One-off ${f.type.label.lowercase()}" },
            fields = f,
            accounts = accounts,
            categories = categories,
            typeEditable = details == null,
            isTransfer = isTransfer,
            accountLabel = if (isTransfer) "From account" else "Account",
            accountHint = if (accounts.isEmpty()) "Add an account first" else null,
            toAccountChoices = accounts.filter { it.id != f.accountId },
            toAccountHint = if (accounts.size < 2) "Transfers need at least two accounts" else null,
            // Scheduled postings without their own category inherit the schedule's.
            categoryNoneLabel = if (isScheduled) "Same as schedule" else "Uncategorized",
            dateHint = if (isScheduled) "Changing the date may show the original occurrence as due again." else null,
            deleteConfirm = details?.takeUnless { linkedSpaceTransfer }?.let {
                ConfirmCopy(
                    title = "Delete transaction?",
                    message = if (isScheduled) {
                        "The occurrence will show as not ${f.type.postedLabel.lowercase()} in Plan again."
                    } else {
                        "Attached receipts are kept and become unattached."
                    },
                    confirmLabel = "Delete",
                    destructive = true
                )
            },
            showsReceipts = details != null,
            receipts = attached.map { AttachedReceiptUi(it, "Added ${formatTimestamp(it.addedAt)}") },
            saveEnabled = l == EditorLoad.READY && canSave(f)
        )
    }

    private fun canSave(f: TransactionFields): Boolean {
        if (existing?.posting?.pairId?.startsWith(finance.shilling.shared.data.store.SPACE_TRANSFER_PREFIX) == true) return false
        val amount = parseAmountInput(f.amountText)
        return f.title.isNotBlank() && amount != null && amount > 0 && f.accountId != null &&
            (f.type != ScheduleType.TRANSFER || (f.toAccountId != null && f.toAccountId != f.accountId))
    }
}

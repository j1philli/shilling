package finance.shilling.shared.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.Receipt
import finance.shilling.shared.data.ReceiptFileStore
import finance.shilling.shared.data.ReceiptWithPosting
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.analytics.ProductAnalytics
import finance.shilling.shared.data.analytics.ProductEvent
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.store.ReceiptRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.math.abs
import kotlin.time.Clock

data class ReceiptFields(
    val name: String = "",
    val receiptDateEpochDay: Long? = null,
    val amountText: String = "",
    val notes: String = ""
)

data class ReceiptEditorUiState(
    val load: EditorLoad = EditorLoad.LOADING,
    val isNew: Boolean = true,
    val title: String = "Receipt",
    val subtitle: String? = null,
    val fields: ReceiptFields = ReceiptFields(),
    /** New receipts: the picked file ("name · size"), or null before one is chosen. */
    val fileLabel: String? = null,
    /** The attached (or, for new receipts, to-be-attached) transaction: "title · date". */
    val attachmentLabel: String? = null,
    val deleteConfirm: ConfirmCopy? = null,
    val saveEnabled: Boolean = false,
    /** Existing receipts: the stored receipt (to open its file). */
    val receipt: Receipt? = null
) {
    val missingMessage: String get() = "This receipt was deleted."
    val noFileLabel: String get() = "No file selected"
    val notAttachedLabel: String get() = "Not attached to a transaction"
    val attachLabel: String get() = if (attachmentLabel == null) "Attach to transaction" else "Change"
}

/** A transaction the receipt can be attached to. */
data class AttachCandidateUi(
    val postingId: String,
    val title: String,
    val supporting: String,
    val type: ScheduleType,
    val amount: String
)

data class AttachPickerUiState(
    val query: String = "",
    val candidates: List<AttachCandidateUi> = emptyList()
) {
    val title: String get() = "Attach to transaction"
    val emptyMessage: String get() = "No transactions in the last six months."
}

/** Adds a receipt (receiptId == null) or edits one's metadata and attachment. */
class ReceiptEditorViewModel(
    private val receiptId: String?,
    private val receiptRepository: ReceiptRepository,
    postingRepository: PostingRepository,
    private val fileStore: ReceiptFileStore,
    private val idGenerator: IdGenerator,
    private val analytics: ProductAnalytics
) : ViewModel() {
    private class PickedFile(val name: String, val bytes: ByteArray)

    private val form = EditorForm(ReceiptFields(), if (receiptId == null) EditorLoad.READY else EditorLoad.LOADING)
    private val file = MutableStateFlow<PickedFile?>(null)
    /** New receipts: the transaction to attach to on save. */
    private val pendingAttach = MutableStateFlow<PostingWithDetails?>(null)
    private val pickerQuery = MutableStateFlow("")

    private val existing = if (receiptId == null) {
        flowOf(null)
    } else {
        receiptRepository.watchAll().map { list -> list.firstOrNull { it.receipt.id == receiptId } }
    }

    val state: StateFlow<ReceiptEditorUiState> = combine(
        form.state, file, pendingAttach, existing
    ) { (f, l), picked, attach, item ->
        build(f, if (receiptId != null && l == EditorLoad.READY && item == null) EditorLoad.MISSING else l, picked, attach, item)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ReceiptEditorUiState(load = form.load, isNew = receiptId == null))

    private val recentPostings: StateFlow<List<PostingWithDetails>> = run {
        val end = today().plus(1, DateTimeUnit.DAY)
        postingRepository.watchBetween(end.minus(6, DateTimeUnit.MONTH), end)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    }

    /** Transactions from the last six months; amount matches, then date matches, float to the top. */
    val picker: StateFlow<AttachPickerUiState> =
        combine(recentPostings, pickerQuery, form.state) { postings, query, (f, _) ->
            val q = query.trim().lowercase()
            val amount = parseAmountInput(f.amountText)
            val date = f.receiptDateEpochDay
            AttachPickerUiState(
                query = query,
                candidates = postings
                    .filterNot { it.isTransferCredit() }
                    .filter { q.isEmpty() || it.title.lowercase().contains(q) || it.accountName?.lowercase()?.contains(q) == true }
                    .sortedWith(
                        compareBy<PostingWithDetails> {
                            if (amount != null && abs(it.posting.amount - amount) < 0.005) 0 else 1
                        }.thenBy {
                            date?.let { d -> abs(it.posting.date.toEpochDays() - d) } ?: 0
                        }.thenByDescending { it.posting.date }
                    )
                    .take(100)
                    .map {
                        AttachCandidateUi(
                            postingId = it.posting.id,
                            title = it.title,
                            supporting = listOfNotNull(formatDate(it.posting.date), it.accountName).joinToString(" · "),
                            type = it.posting.type,
                            amount = formatSigned(it.posting.type, it.posting.amount)
                        )
                    }
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AttachPickerUiState())

    init {
        if (receiptId != null) {
            viewModelScope.launch {
                val receipt = existing.first()?.receipt
                // A deleted receipt shows as missing (see `state`), so READY either way.
                form.loaded(
                    receipt?.let {
                        ReceiptFields(
                            name = it.originalName,
                            receiptDateEpochDay = it.receiptDate,
                            amountText = it.amount?.let(::formatAmountInput).orEmpty(),
                            notes = it.notes.orEmpty()
                        )
                    } ?: form.fields
                )
            }
        }
    }

    fun setName(value: String) = form.update { it.copy(name = value) }
    fun setReceiptDate(epochDay: Long?) = form.update { it.copy(receiptDateEpochDay = epochDay) }
    fun setAmountText(value: String) = form.update { it.copy(amountText = value) }
    fun setNotes(value: String) = form.update { it.copy(notes = value) }
    fun setPickerQuery(value: String) {
        pickerQuery.value = value
    }

    /** New receipts: the picked file; its name becomes the receipt name if none was typed. */
    fun setFile(fileName: String, bytes: ByteArray) {
        file.value = PickedFile(fileName, bytes)
        form.update { if (it.name.isBlank()) it.copy(name = fileName) else it }
    }

    /**
     * Attaches to [postingId]: on save for a new receipt, right away for an existing one (returning
     * its confirmation).
     */
    suspend fun attach(postingId: String): String? {
        val posting = recentPostings.value.firstOrNull { it.posting.id == postingId } ?: return null
        pickerQuery.value = ""
        if (receiptId == null) {
            pendingAttach.value = posting
            return null
        }
        receiptRepository.attach(receiptId, postingId)
        analytics.captureAsync(ProductEvent.RECEIPT_ATTACHED)
        return "Attached to ${posting.title}"
    }

    suspend fun detach(): Undoable? {
        if (receiptId == null) {
            pendingAttach.value = null
            return null
        }
        val previous = existing.first()?.receipt?.postingId ?: return null
        receiptRepository.detach(receiptId)
        return Undoable("Receipt detached") { receiptRepository.attach(receiptId, previous) }
    }

    suspend fun save(): String? {
        if (!state.value.saveEnabled) return null
        val f = form.fields
        val amount = parseAmountInput(f.amountText)
        val notes = f.notes.trim().ifBlank { null }
        if (receiptId != null) {
            receiptRepository.updateMetadata(receiptId, notes, f.receiptDateEpochDay, amount)
            return "Receipt updated"
        }
        val picked = file.value ?: return null
        val id = idGenerator.newId()
        val storedName = f.name.trim()
        receiptRepository.saveWithFile(
            Receipt(
                id = id,
                postingId = pendingAttach.value?.posting?.id,
                filePath = storedName,
                originalName = storedName,
                addedAt = Clock.System.now().toEpochMilliseconds(),
                notes = notes,
                receiptDate = f.receiptDateEpochDay,
                amount = amount
            ), fileStore, picked.bytes
        )
        if (pendingAttach.value != null) analytics.captureAsync(ProductEvent.RECEIPT_ATTACHED)
        return "Receipt saved"
    }

    suspend fun delete(): String? {
        val id = receiptId ?: return null
        fileStore.delete(id)
        receiptRepository.delete(id)
        return "Receipt deleted"
    }

    private fun build(
        f: ReceiptFields,
        l: EditorLoad,
        picked: PickedFile?,
        attach: PostingWithDetails?,
        item: ReceiptWithPosting?
    ): ReceiptEditorUiState {
        val amountValid = f.amountText.isBlank() || parseAmountInput(f.amountText) != null
        if (receiptId == null) {
            return ReceiptEditorUiState(
                load = l,
                isNew = true,
                title = "New receipt",
                fields = f,
                fileLabel = picked?.let { "${it.name} · ${formatFileSize(it.bytes.size)}" },
                attachmentLabel = attach?.let { "${it.title} · ${formatDate(it.posting.date)}" },
                saveEnabled = picked != null && f.name.isNotBlank() && amountValid
            )
        }
        val receipt = item?.receipt
        return ReceiptEditorUiState(
            load = l,
            isNew = false,
            title = receipt?.originalName ?: "Receipt",
            subtitle = receipt?.let { "Added ${formatTimestamp(it.addedAt)}" },
            fields = f,
            attachmentLabel = item?.postingTitle?.let { title ->
                val date = item.postingDate?.let { runCatching { formatDate(LocalDate.parse(it)) }.getOrDefault(it) }
                listOfNotNull(title, date).joinToString(" · ")
            },
            deleteConfirm = ConfirmCopy(
                title = "Delete receipt?",
                message = "The file is removed from this device and your synced devices. This can't be undone.",
                confirmLabel = "Delete receipt",
                destructive = true
            ),
            saveEnabled = l == EditorLoad.READY && amountValid,
            receipt = receipt
        )
    }
}

/** The incoming leg of a transfer (scheduled `_cr` legs, or an ad-hoc pair's income side). */
private fun PostingWithDetails.isTransferCredit(): Boolean =
    posting.id.endsWith("_cr") ||
        (posting.scheduleId == null && posting.pairId != null && posting.type == ScheduleType.INCOME)

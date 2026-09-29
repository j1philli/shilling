package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Close
import com.composables.icons.materialicons.filled.Search
import com.composables.icons.materialicons.filled.Upload
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.data.store.PostingRepository
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import org.koin.compose.koinInject
import finance.shilling.shared.presentation.formatDayHeader
import finance.shilling.shared.presentation.today

private val activityRanges = listOf(1 to "1M", 3 to "3M", 6 to "6M", 12 to "1Y")

/** An activity row; transfers collapse their debit/credit legs into one entry. */
private data class ActivityRow(
    val item: PostingWithDetails,
    val type: ScheduleType,
    val toAccountName: String?
)

private fun mergeTransferLegs(postings: List<PostingWithDetails>): List<ActivityRow> {
    val byId = postings.associateBy { it.posting.id }
    val consumed = mutableSetOf<String>()
    return postings.mapNotNull { item ->
        val p = item.posting
        if (p.id in consumed) return@mapNotNull null
        val partner = when {
            p.id.endsWith("_dr") -> byId[p.id.removeSuffix("_dr") + "_cr"]
            p.id.endsWith("_cr") -> byId[p.id.removeSuffix("_cr") + "_dr"]
            p.scheduleId == null && p.pairId != null ->
                postings.firstOrNull { it.posting.pairId == p.pairId && it.posting.id != p.id && it.posting.scheduleId == null }
            else -> null
        }
        if (partner == null) return@mapNotNull ActivityRow(item, p.type, null)
        consumed += partner.posting.id
        val (debit, credit) = if (p.type == ScheduleType.EXPENSE) item to partner else partner to item
        ActivityRow(debit, ScheduleType.TRANSFER, credit.accountName)
    }
}

@Composable
fun ActivityView(
    onOpenTransaction: (String?) -> Unit,
    onOpenImport: () -> Unit
) {
    val postingRepo = koinInject<PostingRepository>()
    val todayDate = remember { today() }
    var rangeMonths by rememberSaveable { mutableIntStateOf(1) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    val rangeStart = remember(rangeMonths, todayDate) { todayDate.minus(rangeMonths, DateTimeUnit.MONTH) }
    // Include items recorded ahead of their date (e.g. a bill marked paid early).
    val rangeEnd = remember(todayDate) { todayDate.plus(60, DateTimeUnit.DAY) }
    val postings by remember(rangeStart, rangeEnd) { postingRepo.watchBetween(rangeStart, rangeEnd) }
        .collectAsState(initial = emptyList())
    val rows = remember(postings) { mergeTransferLegs(postings) }
    val filtered = remember(rows, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) rows else rows.filter { row ->
            listOfNotNull(row.item.title, row.item.accountName, row.item.categoryName, row.toAccountName)
                .any { it.lowercase().contains(q) }
        }
    }

    ListDetailLayout(
        selectedKey = selectedKey,
        onDismissDetail = { selectedKey = null },
        list = { twoPane ->
            val open: (String?) -> Unit = { id ->
                if (twoPane) selectedKey = id ?: NEW_ITEM_KEY else onOpenTransaction(id)
            }
            ScreenScaffold(
                title = "Activity",
                actions = {
                    OutlinedButton(
                        onClick = onOpenImport,
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding
                    ) {
                        Icon(MaterialIcons.Filled.Upload, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text("Import", modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                    AddButton("Add", onClick = { open(null) })
                }
            ) { padding ->
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                    item(key = "filters") {
                        Column {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Search transactions") },
                                leadingIcon = { Icon(MaterialIcons.Filled.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(MaterialIcons.Filled.Close, contentDescription = "Clear search")
                                        }
                                    }
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
                            )
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier.fillMaxWidth().padding(top = Spacing.md)
                            ) {
                                activityRanges.forEachIndexed { index, (months, label) ->
                                    SegmentedButton(
                                        selected = rangeMonths == months,
                                        onClick = { rangeMonths = months },
                                        shape = SegmentedButtonDefaults.itemShape(index, activityRanges.size)
                                    ) { Text(label) }
                                }
                            }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "empty") {
                            if (searchQuery.isNotBlank()) {
                                EmptyState(title = "No matches", message = "Nothing matches \"${searchQuery.trim()}\" in this period.")
                            } else {
                                EmptyState(
                                    title = "No transactions yet",
                                    message = "Record items from Plan, add one-off transactions, or import a bank CSV.",
                                    actionLabel = "Add transaction",
                                    onAction = { open(null) },
                                    secondaryActionLabel = "Import from CSV",
                                    onSecondaryAction = onOpenImport
                                )
                            }
                        }
                    } else {
                        filtered.groupBy { it.item.posting.date }.entries
                            .sortedByDescending { it.key }
                            .forEach { (date, dayRows) ->
                                item(key = "header-$date") { ListSectionHeader(formatDayHeader(date, todayDate)) }
                                items(dayRows, key = { it.item.posting.id }) { row ->
                                    ActivityRowItem(
                                        row = row,
                                        selected = twoPane && selectedKey == row.item.posting.id,
                                        onClick = { open(row.item.posting.id) }
                                    )
                                }
                            }
                    }
                }
            }
        },
        detail = { key ->
            TransactionEditor(
                postingId = key.takeUnless { it == NEW_ITEM_KEY },
                navIcon = ScreenNavIcon.CLOSE,
                onClose = { selectedKey = null },
                onSaved = { selectedKey = null }
            )
        },
        emptyDetail = {
            EmptyState(title = "No transaction selected", message = "Choose a transaction to see its details and receipts.")
        }
    )
}

@Composable
private fun ActivityRowItem(row: ActivityRow, selected: Boolean, onClick: () -> Unit) {
    val item = row.item
    val supporting = buildList {
        val account = item.accountName ?: "No account"
        add(if (row.type == ScheduleType.TRANSFER) "$account → ${row.toAccountName ?: "No account"}" else account)
        item.categoryName?.let { add(it) }
        if (item.posting.scheduleId == null) add("One-off")
    }.joinToString(" · ")
    EntityListItem(
        title = item.title,
        supporting = supporting,
        leading = { ColorDot(colorFromHex(item.categoryColor)) },
        trailing = { AmountText(row.type, item.posting.amount) },
        selected = selected,
        onClick = onClick
    )
}

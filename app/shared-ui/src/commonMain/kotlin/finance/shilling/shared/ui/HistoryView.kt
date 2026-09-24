package finance.shilling.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Search
import com.composables.icons.materialicons.filled.Add
import com.composables.icons.materialicons.filled.Arrow_back
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.store.CategoryRepository
import finance.shilling.shared.data.store.PostingRepository
import finance.shilling.shared.data.PostingWithDetails
import finance.shilling.shared.data.ScheduleType
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryView() {
    val accountRepo = koinInject<AccountRepository>()
    val categoryRepo = koinInject<CategoryRepository>()
    val postingRepo = koinInject<PostingRepository>()
    val scope = rememberCoroutineScope()
    val systemZone = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.now().toLocalDateTime(systemZone).date }
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())

    var rangeMonths by remember { mutableStateOf(1) }
    val rangeStart = remember(rangeMonths, today) { today.minus(rangeMonths, DateTimeUnit.MONTH) }
    val rangeEnd = remember(today) { today.plus(1, DateTimeUnit.DAY) }
    val postings by postingRepo.watchBetween(rangeStart, rangeEnd).collectAsState(initial = emptyList())

    var searchQuery by remember { mutableStateOf("") }
    var showAddPage by remember { mutableStateOf(false) }

    if (showAddPage) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(onClick = { showAddPage = false }, tooltip = "Back to history") {
                    Icon(MaterialIcons.Filled.Arrow_back, contentDescription = "Back")
                }
                Text("New transaction", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            ShillingCard {
                QuickAddForm(
                    accounts = accounts,
                    categories = categories,
                    onAdd = { title, amount, type, accountId, categoryId, date ->
                        scope.launch {
                            postingRepo.recordAdHoc(title, amount, type, accountId, categoryId, date)
                            showAddPage = false
                        }
                    }
                )
            }
        }
        return
    }

    val filtered = remember(postings, searchQuery) {
        if (searchQuery.isBlank()) postings
        else {
            val q = searchQuery.lowercase()
            postings.filter { item ->
                item.title.lowercase().contains(q) ||
                    (item.accountName?.lowercase()?.contains(q) == true) ||
                    (item.categoryName?.lowercase()?.contains(q) == true)
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = this.maxWidth >= 900.dp
        val currentMaxWidth = this.maxWidth
        if (isWide) {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Column(
                    modifier = Modifier.width(currentMaxWidth * 0.35f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SectionHeader("Transaction History", "View past transactions and record one-off expenses.")
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search transactions") },
                        leadingIcon = { Icon(MaterialIcons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(20.dp)) },
                        singleLine = true
                    )
                    SingleChoiceSegmentedButtonRow {
                        listOf(1 to "1M", 3 to "3M", 6 to "6M", 12 to "1Y").forEachIndexed { index, (months, label) ->
                            SegmentedButton(
                                selected = rangeMonths == months,
                                onClick = { rangeMonths = months },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 4)
                            ) { Text(label) }
                        }
                    }
                    Text("${filtered.size} transactions", style = MaterialTheme.typography.labelMedium)
                    ShillingDivider()
                    Button(onClick = { showAddPage = true }) {
                        Icon(MaterialIcons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add transaction")
                    }
                }
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    PostingsList(filtered)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SectionHeader("Transaction History", "View past transactions and record one-off expenses.")
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search transactions") },
                            leadingIcon = { Icon(MaterialIcons.Filled.Search, contentDescription = "Search", modifier = Modifier.size(20.dp)) },
                            singleLine = true
                        )
                        SingleChoiceSegmentedButtonRow {
                            listOf(1 to "1M", 3 to "3M", 6 to "6M", 12 to "1Y").forEachIndexed { index, (months, label) ->
                                SegmentedButton(
                                    selected = rangeMonths == months,
                                    onClick = { rangeMonths = months },
                                    shape = SegmentedButtonDefaults.itemShape(index = index, count = 4)
                                ) { Text(label) }
                            }
                        }
                        Button(onClick = { showAddPage = true }) {
                            Icon(MaterialIcons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add transaction")
                        }
                        Text("${filtered.size} transactions", style = MaterialTheme.typography.labelMedium)
                        ShillingDivider()
                    }
                }
                if (filtered.isEmpty()) {
                    item { Text("No transactions found.") }
                } else {
                    filtered.groupBy { it.posting.date }.entries.sortedByDescending { it.key }.forEach { (date, dayItems) ->
                        item(key = "header-$date") {
                            Text(date.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp))
                            ShillingDivider()
                        }
                        items(dayItems, key = { it.posting.id }) { posting ->
                            PostingRow(posting)
                            ShillingDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PostingsList(
    postings: List<PostingWithDetails>,
    modifier: Modifier = Modifier
) {
    if (postings.isEmpty()) {
        Column(modifier = modifier.padding(vertical = 8.dp)) {
            Text("No transactions found.")
        }
        return
    }
    val grouped = postings.groupBy { it.posting.date }.entries.sortedByDescending { it.key }
    LazyColumn(modifier = modifier.fillMaxWidth()) {
        grouped.forEach { (date, items) ->
            item(key = "header-$date") {
                Text(date.toString(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 4.dp))
                ShillingDivider()
            }
            items(items, key = { it.posting.id }) { item ->
                PostingRow(item)
                ShillingDivider()
            }
        }
    }
}

@Composable
private fun PostingRow(item: PostingWithDetails) {
    val posting = item.posting
    val amountColor = when (posting.type) {
        ScheduleType.EXPENSE -> MaterialTheme.colorScheme.error
        ScheduleType.INCOME -> MaterialTheme.colorScheme.tertiary
        ScheduleType.TRANSFER -> MaterialTheme.colorScheme.primary
    }
    val categoryColor = remember(item.categoryColor) { colorFromHex(item.categoryColor) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (categoryColor != null) {
                Box(
                    modifier = Modifier
                        .height(12.dp)
                        .width(12.dp)
                        .background(color = categoryColor, shape = MaterialTheme.shapes.small)
                )
            }
            Column {
                Text(item.title, fontWeight = FontWeight.Medium)
                val subtitle = buildString {
                    item.accountName?.let { append(it) }
                    item.categoryName?.let {
                        if (isNotEmpty()) append(" • ")
                        append(it)
                    }
                    if (posting.scheduleId == null) {
                        if (isNotEmpty()) append(" • ")
                        append("ad-hoc")
                    }
                }
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            formatSigned(posting.type, posting.amount),
            color = amountColor,
            style = MaterialTheme.typography.bodyLarge
        )
    }
}

@Composable
private fun QuickAddForm(
    accounts: List<Account>,
    categories: List<Category>,
    onAdd: (String, Double, ScheduleType, String, String?, LocalDate) -> Unit
) {
    val systemZone = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.now().toLocalDateTime(systemZone).date }
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ScheduleType.EXPENSE) }
    var selectedAccountId by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var selectedCategoryId by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(today.toString()) }

    val amount = amountText.toDoubleOrNull()
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val enableSave = title.isNotBlank() && amount != null && date != null && selectedAccountId.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Description") },
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it },
                modifier = Modifier.weight(1f),
                label = { Text("Amount") },
                placeholder = { Text("0.00") },
                singleLine = true
            )
            ButtonDropdown(
                label = type.name.lowercase().replaceFirstChar { it.titlecase() },
                options = listOf(ScheduleType.EXPENSE, ScheduleType.INCOME),
                onSelect = { type = it },
                itemLabel = { it.name.lowercase().replaceFirstChar { c -> c.titlecase() } }
            )
        }
        OutlinedTextField(
            value = dateText,
            onValueChange = { dateText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Date (yyyy-MM-dd)") },
            singleLine = true
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Account", style = MaterialTheme.typography.labelMedium)
            ButtonDropdown(
                label = accounts.firstOrNull { it.id == selectedAccountId }?.name ?: "Select",
                options = accounts,
                onSelect = { selectedAccountId = it.id },
                itemLabel = { it.name },
                enabled = accounts.isNotEmpty()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Category", style = MaterialTheme.typography.labelMedium)
            ButtonDropdown(
                label = categories.firstOrNull { it.id == selectedCategoryId }?.name ?: "Uncategorized",
                options = categories,
                onSelect = { selectedCategoryId = it.id },
                itemLabel = { it.name },
                leadingOption = "Uncategorized" to { selectedCategoryId = "" }
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (enableSave) {
                        onAdd(title.trim(), amount!!, type, selectedAccountId, selectedCategoryId.takeIf { it.isNotBlank() }, date!!)
                        title = ""
                        amountText = ""
                        dateText = today.toString()
                    }
                },
                enabled = enableSave
            ) { Text("Save") }
            TextButton(onClick = {
                title = ""
                amountText = ""
                dateText = today.toString()
                selectedCategoryId = ""
            }) { Text("Clear") }
        }
    }
}

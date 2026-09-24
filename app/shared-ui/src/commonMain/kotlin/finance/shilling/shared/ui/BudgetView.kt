package finance.shilling.shared.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Chevron_left
import com.composables.icons.materialicons.filled.Chevron_right
import com.composables.icons.materialicons.filled.Expand_more
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import finance.shilling.shared.data.Account
import finance.shilling.shared.data.BudgetLine
import finance.shilling.shared.data.BudgetSummary
import finance.shilling.shared.data.store.AccountRepository
import finance.shilling.shared.data.usecase.ComputeBudgetUseCase
import kotlin.time.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetView() {
    val accountRepo = koinInject<AccountRepository>()
    val budgetUseCase = koinInject<ComputeBudgetUseCase>()
    val accounts by accountRepo.watchAll().collectAsState(initial = emptyList())
    val systemZone = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.now().toLocalDateTime(systemZone).date }
    var datePickerVisible by remember { mutableStateOf(false) }
    val utc = remember { TimeZone.UTC }
    val initialMonth = remember { today.startOfMonth() }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialMonth.atStartOfDayIn(utc).toEpochMilliseconds()
    )
    var monthStart by remember { mutableStateOf(initialMonth) }
    val summary by produceState(initialValue = BudgetSummary.empty(monthStart, monthStart.plus(1, DateTimeUnit.MONTH)), monthStart) {
        budgetUseCase.watchBudget(monthStart).collect { value = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("Monthly Budget", "Review a one-line summary for every schedule plus category totals.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TooltipIconButton(onClick = { monthStart = monthStart.minus(1, DateTimeUnit.MONTH).startOfMonth() }, tooltip = "Previous month") {
                Icon(MaterialIcons.Filled.Chevron_left, contentDescription = "Previous month", modifier = Modifier.size(24.dp))
            }
            TooltipIconButton(onClick = { monthStart = monthStart.plus(1, DateTimeUnit.MONTH).startOfMonth() }, tooltip = "Next month") {
                Icon(MaterialIcons.Filled.Chevron_right, contentDescription = "Next month", modifier = Modifier.size(24.dp))
            }
            Button(onClick = { datePickerVisible = true }) { Text("Jump to month") }
        }
        Text("${monthStart.month.name.lowercase().replaceFirstChar { it.titlecase() }} ${monthStart.year}", style = MaterialTheme.typography.titleMedium)
        ShillingDivider()
        if (summary.lines.isEmpty()) {
            Text("No scheduled items in this month.")
        } else {
            CategoryBudgetList(summary = summary, accounts = accounts)
        }
        val netLabel = if (summary.netChange >= 0) "Surplus" else "Deficit"
        val netColor = if (summary.netChange >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
        Text("$netLabel: ${formatCurrency(summary.netChange)}", fontWeight = FontWeight.Bold, color = netColor)
    }

    if (datePickerVisible) {
        DatePickerDialog(
            onDismissRequest = { datePickerVisible = false },
            confirmButton = {
                TextButton(onClick = {
                    val millis = datePickerState.selectedDateMillis
                    if (millis != null) {
                        val picked = Instant.fromEpochMilliseconds(millis).toLocalDateTime(utc).date
                        monthStart = picked.startOfMonth()
                    }
                    datePickerVisible = false
                }) { Text("Use month") }
            },
            dismissButton = { TextButton(onClick = { datePickerVisible = false }) { Text("Cancel") } }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

@Composable
private fun BudgetLineRow(line: BudgetLine, accountName: String?) {
    ShillingCard {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text(line.title, fontWeight = FontWeight.Bold)
            val subtitle = buildString {
                append(line.type.name.lowercase().replaceFirstChar { it.titlecase() })
                accountName?.let { append(" • $it") }
                line.category?.let { append(" • ${it.name}") }
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
            Text("${line.occurrences} × ${formatCurrency(line.amountPerOccurrence)} = ${formatCurrency(line.totalAmount)}")
        }
    }
}

@Composable
private fun CategoryBudgetList(summary: BudgetSummary, accounts: List<Account>) {
    val grouped = remember(summary.lines) { summary.lines.groupBy { it.category?.id } }
    val expanded = remember { mutableStateOf(setOf<String?>()) }

    grouped.entries.sortedBy { it.value.firstOrNull()?.category?.name ?: "" }.forEach { (categoryId, lines) ->
        val category = lines.firstOrNull()?.category
        val total = summary.categoryTotals.firstOrNull { it.category?.id == categoryId }?.total ?: 0.0
        val isOpen = expanded.value.contains(categoryId)
        ShillingCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded.value = if (isOpen) {
                            expanded.value - categoryId
                        } else {
                            expanded.value + categoryId
                        }
                    }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val name = category?.name ?: "Uncategorized"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isOpen) MaterialIcons.Filled.Expand_more else MaterialIcons.Filled.Chevron_right,
                        contentDescription = if (isOpen) "Collapse" else "Expand",
                        modifier = Modifier.size(20.dp)
                    )
                    Text(name, fontWeight = FontWeight.Bold)
                }
                Text(formatCurrency(total))
            }
        }
        if (isOpen) {
            lines.forEach { line ->
                BudgetLineRow(
                    line = line,
                    accountName = accounts.firstOrNull { it.id == line.accountId }?.name
                )
            }
        }
        ShillingDivider()
    }
}

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Close
import com.composables.icons.materialicons.filled.Search
import com.composables.icons.materialicons.filled.Upload
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import finance.shilling.shared.presentation.ActivityRowUi
import finance.shilling.shared.presentation.ActivityViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ActivityView(
    onOpenTransaction: (String?) -> Unit,
    onOpenImport: () -> Unit,
    viewModel: ActivityViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(searchQuery) { viewModel.setQuery(searchQuery) }

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
                                state.ranges.forEachIndexed { index, range ->
                                    SegmentedButton(
                                        selected = state.selectedRange == range,
                                        onClick = { viewModel.setRange(range.months) },
                                        shape = SegmentedButtonDefaults.itemShape(index, state.ranges.size)
                                    ) { Text(range.label) }
                                }
                            }
                        }
                    }
                    val empty = state.empty
                    if (empty != null) {
                        item(key = "empty") {
                            if (empty.showActions) {
                                EmptyState(
                                    title = empty.title,
                                    message = empty.message,
                                    actionLabel = "Add transaction",
                                    onAction = { open(null) },
                                    secondaryActionLabel = "Import from CSV",
                                    onSecondaryAction = onOpenImport
                                )
                            } else {
                                EmptyState(title = empty.title, message = empty.message)
                            }
                        }
                    } else {
                        state.sections.forEach { section ->
                            item(key = "header-${section.header}") { ListSectionHeader(section.header) }
                            items(section.rows, key = { it.id }) { row ->
                                ActivityRowItem(
                                    row = row,
                                    selected = twoPane && selectedKey == row.id,
                                    onClick = { open(row.id) }
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
private fun ActivityRowItem(row: ActivityRowUi, selected: Boolean, onClick: () -> Unit) {
    EntityListItem(
        title = row.title,
        supporting = row.supporting,
        leading = { ColorDot(colorFromHex(row.categoryColor)) },
        trailing = {
            Text(row.amount, color = amountColor(row.type), style = MaterialTheme.typography.bodyLarge)
        },
        selected = selected,
        onClick = onClick
    )
}

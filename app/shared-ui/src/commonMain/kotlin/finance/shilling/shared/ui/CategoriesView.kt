package finance.shilling.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Check
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.store.CategoryRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import finance.shilling.shared.presentation.CategoriesViewModel
import org.koin.compose.viewmodel.koinViewModel
import finance.shilling.shared.presentation.CategoryEditorViewModel
import finance.shilling.shared.presentation.EditorLoad
import org.koin.core.parameter.parametersOf

@Composable
fun CategoriesView(onOpenCategory: (String?) -> Unit,
    title: String = "Categories",
    headerBottom: (@Composable () -> Unit)? = null,
    viewModel: CategoriesViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsState()
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    ListDetailLayout(
        selectedKey = selectedKey,
        onDismissDetail = { selectedKey = null },
        list = { twoPane ->
            val open: (String?) -> Unit = { id ->
                if (twoPane) selectedKey = id ?: NEW_ITEM_KEY else onOpenCategory(id)
            }
            ScreenScaffold(
                title = title,
                headerBottom = headerBottom,
                actions = { AddButton("Add", onClick = { open(null) }) }
            ) { padding ->
                val empty = state.empty
                if (empty != null) {
                    EmptyState(
                        title = empty.title,
                        message = empty.message,
                        actionLabel = empty.actionLabel,
                        onAction = { open(null) }
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        items(state.rows, key = { it.id }) { category ->
                            EntityListItem(
                                title = category.name,
                                leading = { ColorDot(colorFromHex(category.color), size = 16) },
                                selected = twoPane && selectedKey == category.id,
                                onClick = { open(category.id) }
                            )
                        }
                    }
                }
            }
        },
        detail = { key ->
            CategoryEditor(
                categoryId = key.takeUnless { it == NEW_ITEM_KEY },
                navIcon = ScreenNavIcon.CLOSE,
                onClose = { selectedKey = null },
                onSaved = { selectedKey = null }
            )
        },
        emptyDetail = { EmptyState(title = "No category selected", message = "Choose a category to edit it.") }
    )
}

@Composable
fun CategoryEditor(
    categoryId: String?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val viewModel = koinViewModel<CategoryEditorViewModel>(key = "category-${categoryId ?: "new"}") { parametersOf(categoryId) }
    val state by viewModel.state.collectAsState()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    when (state.load) {
        EditorLoad.LOADING -> EditorPlaceholder("Category", navIcon, onClose, loading = true, missingMessage = "")
        EditorLoad.MISSING -> EditorPlaceholder("Category", navIcon, onClose, loading = false, missingMessage = state.missingMessage)
        EditorLoad.READY -> EditorScaffold(
            title = state.title,
            navIcon = navIcon,
            onClose = onClose,
            saveEnabled = state.saveEnabled,
            onSave = {
                scope.launch {
                    viewModel.save()?.let {
                        snackbar.show(it)
                        onSaved()
                    }
                }
            },
            delete = state.deleteConfirm?.let { copy ->
                DeleteConfirmation(
                    title = copy.title,
                    message = copy.message,
                    confirmLabel = copy.confirmLabel,
                    onConfirm = {
                        scope.launch {
                            val message = viewModel.delete()
                            onSaved()
                            message?.let(snackbar::show)
                        }
                    }
                )
            }
        ) {
            TextInputField(value = state.name, onValueChange = viewModel::setName, label = "Name", placeholder = "e.g. Groceries")
            Text("Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                state.swatches.forEach { swatch ->
                    val isSelected = swatch.hex.equals(state.color, ignoreCase = true)
                    val fill = colorFromHex(swatch.hex) ?: Color.Gray
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .border(
                                width = if (isSelected) 3.dp else 0.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable(role = Role.RadioButton) { viewModel.setColor(swatch.hex) }
                            .semantics {
                                contentDescription = swatch.name
                                selected = isSelected
                            }
                    ) {
                        Box(modifier = Modifier.size(32.dp).background(fill, CircleShape))
                        if (isSelected) {
                            Icon(MaterialIcons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

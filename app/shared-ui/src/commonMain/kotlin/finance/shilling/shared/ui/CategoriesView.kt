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

private data class Swatch(val hex: String, val name: String)

private val categorySwatches = listOf(
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
    Swatch("#78909C", "Slate"),
)

@Composable
fun CategoriesView(onOpenCategory: (String?) -> Unit,
    title: String = "Categories",
    headerBottom: (@Composable () -> Unit)? = null
) {
    val categoryRepo = koinInject<CategoryRepository>()
    val categories by remember { categoryRepo.watchAll() }.collectAsState(initial = emptyList())
    val sorted = remember(categories) { categories.sortedBy { it.name.lowercase() } }
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
                if (sorted.isEmpty()) {
                    EmptyState(
                        title = "No categories yet",
                        message = "Categories group schedules and transactions in Plan.",
                        actionLabel = "Add category",
                        onAction = { open(null) }
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        items(sorted, key = { it.id }) { category ->
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
    val categoryRepo = koinInject<CategoryRepository>()
    if (categoryId == null) {
        CategoryForm(existing = null, navIcon = navIcon, onClose = onClose, onSaved = onSaved)
        return
    }
    val loadable = rememberLoadable(categoryId) {
        categoryRepo.watchAll().map { list -> list.firstOrNull { it.id == categoryId } }
    }
    when (loadable) {
        Loadable.Loading -> EditorPlaceholder("Category", navIcon, onClose, loading = true, missingMessage = "")
        is Loadable.Ready -> loadable.value?.let { category ->
            CategoryForm(existing = category, navIcon = navIcon, onClose = onClose, onSaved = onSaved)
        } ?: EditorPlaceholder("Category", navIcon, onClose, loading = false, missingMessage = "This category was deleted.")
    }
}

@Composable
private fun CategoryForm(
    existing: Category?,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    onSaved: () -> Unit
) {
    val categoryRepo = koinInject<CategoryRepository>()
    val idGen = koinInject<IdGenerator>()
    val snackbar = LocalSnackbarController.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var color by rememberSaveable(existing?.id) {
        mutableStateOf(existing?.color ?: categorySwatches.random().hex)
    }

    EditorScaffold(
        title = existing?.name ?: "New category",
        navIcon = navIcon,
        onClose = onClose,
        saveEnabled = name.isNotBlank(),
        onSave = {
            scope.launch {
                categoryRepo.upsert(Category(id = existing?.id ?: idGen.newId(), name = name.trim(), color = color))
                snackbar.show(if (existing == null) "Category added" else "Category updated")
                onSaved()
            }
        },
        delete = existing?.let { category ->
            DeleteConfirmation(
                title = "Delete ${category.name}?",
                message = "Schedules and transactions in this category become Uncategorized.",
                confirmLabel = "Delete category",
                onConfirm = {
                    scope.launch {
                        categoryRepo.delete(category.id)
                        onSaved()
                        snackbar.show("${category.name} deleted")
                    }
                }
            )
        }
    ) {
        TextInputField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "e.g. Groceries")
        Text("Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            val customColor = color.takeIf { current -> categorySwatches.none { it.hex.equals(current, ignoreCase = true) } }
            (categorySwatches + listOfNotNull(customColor?.let { Swatch(it, "Custom") })).forEach { swatch ->
                val isSelected = swatch.hex.equals(color, ignoreCase = true)
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
                        .clickable(role = Role.RadioButton) { color = swatch.hex }
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

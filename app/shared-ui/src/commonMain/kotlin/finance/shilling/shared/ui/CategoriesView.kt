package finance.shilling.shared.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import finance.shilling.shared.data.Category
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.store.CategoryRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun CategoriesView() {
    val idGen = koinInject<IdGenerator>()
    val categoryRepo = koinInject<CategoryRepository>()
    val scope = rememberCoroutineScope()
    val categories by categoryRepo.watchAll().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("Categories", "Organize schedules and future transactions with reusable labels.")
        AddSectionToggle(
            label = "Add category",
            expanded = showAdd,
            onToggle = { showAdd = !showAdd }
        )
        if (showAdd) {
            ShillingCard {
                AddCategoryForm(onAdd = { name, color ->
                    scope.launch {
                        categoryRepo.upsert(
                            Category(
                                id = idGen.newId(),
                                name = name,
                                color = color.ifBlank { null }
                            )
                        )
                    }
                })
            }
        }
        ShillingDivider()
        if (categories.isEmpty()) {
            Text("No categories yet.")
        } else {
            categories.forEach { category ->
                ShillingCard {
                    CategoryRow(
                        category = category,
                        onDelete = { id -> scope.launch { categoryRepo.delete(id) } }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddCategoryForm(
    onAdd: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var color by remember { mutableStateOf("#9C27B0") }
    val enableSave = name.isNotBlank()
    val swatches = listOf(
        "#EF5350",
        "#EC407A",
        "#AB47BC",
        "#7E57C2",
        "#5C6BC0",
        "#42A5F5",
        "#26C6DA",
        "#26A69A",
        "#66BB6A",
        "#9CCC65",
        "#FFCA28",
        "#FFA726",
        "#8D6E63"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add category", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Name") },
            singleLine = true,
            maxLines = 1
        )
        OutlinedTextField(
            value = color,
            onValueChange = { color = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Color hex (optional)") }
        )
        Text("Pick a color", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            swatches.forEach { hex ->
                val swatch = colorFromHex(hex)
                Box(
                    modifier = Modifier
                        .height(28.dp)
                        .width(28.dp)
                        .background(color = swatch ?: MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(6.dp))
                        .clickable { color = hex }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (enableSave) {
                        onAdd(name.trim(), color.trim())
                        name = ""
                        color = "#9C27B0"
                    }
                },
                enabled = enableSave
            ) { Text("Save category") }
            TextButton(onClick = {
                name = ""
                color = "#9C27B0"
            }) { Text("Clear") }
        }
    }
}

@Composable
private fun CategoryRow(
    category: Category,
    onDelete: (String) -> Unit
) {
    val swatch = remember(category.color) { colorFromHex(category.color) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (swatch != null) {
                Box(
                    modifier = Modifier
                        .height(20.dp)
                        .width(20.dp)
                        .background(color = swatch, shape = RoundedCornerShape(6.dp))
                )
            }
            Text(category.name, fontWeight = FontWeight.Bold)
        }
        TooltipIconButton(onClick = { onDelete(category.id) }, tooltip = "Delete category") {
            Icon(MaterialIcons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

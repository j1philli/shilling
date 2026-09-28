package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Delete

/** What to ask before deleting from an editor. */
data class DeleteConfirmation(
    val title: String,
    val message: String,
    val confirmLabel: String = "Delete",
    val onConfirm: () -> Unit
)

/**
 * Chrome for every create/edit screen: title bar with close/back and delete, a scrolling
 * form column capped at a readable width, and Save / Cancel at the end of the form.
 */
@Composable
fun EditorScaffold(
    title: String,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    subtitle: String? = null,
    saveLabel: String = "Save",
    delete: DeleteConfirmation? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ScreenScaffold(
        title = title,
        subtitle = subtitle,
        navIcon = navIcon,
        onNavIcon = onClose,
        actions = {
            if (delete != null) {
                TooltipIconButton(onClick = { confirmDelete = true }, tooltip = delete.confirmLabel) {
                    Icon(
                        MaterialIcons.Filled.Delete,
                        contentDescription = delete.confirmLabel,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    ) { padding ->
        ReadableColumn(padding = padding, maxWidth = 640.dp) {
            content()
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Button(onClick = onSave, enabled = saveEnabled) { Text(saveLabel) }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        }
    }
    if (confirmDelete && delete != null) {
        ConfirmDialog(
            title = delete.title,
            message = delete.message,
            confirmLabel = delete.confirmLabel,
            onConfirm = delete.onConfirm,
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Placeholder for an editor whose entity is still loading or no longer exists. */
@Composable
fun EditorPlaceholder(
    title: String,
    navIcon: ScreenNavIcon,
    onClose: () -> Unit,
    loading: Boolean,
    missingMessage: String
) {
    ScreenScaffold(title = title, navIcon = navIcon, onNavIcon = onClose) {
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            EmptyState(title = "Not found", message = missingMessage)
        }
    }
}

/** Distinguishes "still loading" from "loaded, but the entity doesn't exist". */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Ready<T>(val value: T?) : Loadable<T>
}

@Composable
fun <T> rememberLoadable(key: Any?, flow: () -> kotlinx.coroutines.flow.Flow<T?>): Loadable<T> {
    val state by androidx.compose.runtime.produceState<Loadable<T>>(Loadable.Loading, key) {
        flow().collect { value = Loadable.Ready(it) }
    }
    return state
}

/**
 * Full-size vertical scroll area whose content is capped at [maxWidth] for readability.
 * The cap lives on an inner column: applying widthIn after fillMaxSize has no effect.
 */
@Composable
fun ReadableColumn(
    padding: androidx.compose.foundation.layout.PaddingValues,
    maxWidth: androidx.compose.ui.unit.Dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding)
    ) {
        Column(
            modifier = Modifier.widthIn(max = maxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content
        )
    }
}

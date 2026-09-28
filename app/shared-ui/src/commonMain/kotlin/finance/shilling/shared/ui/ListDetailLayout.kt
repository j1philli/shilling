package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState

/** Sentinel detail key for "create a new item". */
const val NEW_ITEM_KEY = "__new__"

/**
 * List/detail layout used by every entity screen.
 *
 * Two-pane (enough width): list on the left, [detail] for the current [selectedKey] (or
 * [emptyDetail]) on the right. Single-pane: only the list; screens navigate to a detail
 * route instead. [list] is told which mode is active so item taps can pick the right path.
 */
@Composable
fun ListDetailLayout(
    selectedKey: String?,
    /** Clears the selection; system Back calls it while a detail pane is open. */
    onDismissDetail: () -> Unit,
    list: @Composable (twoPane: Boolean) -> Unit,
    detail: @Composable (key: String) -> Unit,
    emptyDetail: @Composable () -> Unit = {
        EmptyState(title = "Nothing selected", message = "Choose an item from the list.")
    }
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= 720.dp
        if (!twoPane) {
            list(false)
            return@BoxWithConstraints
        }
        NavigationBackHandler(
            state = rememberNavigationEventState(NavigationEventInfo.None),
            isBackEnabled = selectedKey != null,
            onBackCompleted = onDismissDetail
        )
        val listWidth = (maxWidth * 0.4f).coerceIn(300.dp, 420.dp)
        Row(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.width(listWidth).fillMaxHeight()) { list(true) }
            VerticalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                if (selectedKey != null) detail(selectedKey) else emptyDetail()
            }
        }
    }
}

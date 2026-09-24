package finance.shilling.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.composables.icons.materialicons.MaterialIcons
import com.composables.icons.materialicons.filled.Chevron_left

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackNavigationScaffold(
    onBack: () -> Unit,
    topPadding: Dp = 0.dp,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        topBar = {
            Column {
                if (topPadding > 0.dp) Spacer(Modifier.height(topPadding))
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MaterialIcons.Filled.Chevron_left,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        },
        content = content
    )
}

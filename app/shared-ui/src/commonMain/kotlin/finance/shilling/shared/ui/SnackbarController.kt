package finance.shilling.shared.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App-level feedback channel. Messages and undo actions run in the shell's scope so they
 * survive the screen that triggered them being popped.
 */
class SnackbarController(
    private val hostState: SnackbarHostState?,
    private val scope: CoroutineScope?
) {
    fun show(
        message: String,
        actionLabel: String? = null,
        onAction: (suspend () -> Unit)? = null
    ) {
        val host = hostState ?: return
        val launchScope = scope ?: return
        launchScope.launch {
            host.currentSnackbarData?.dismiss()
            val result = host.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                withDismissAction = actionLabel != null,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    fun showUndo(message: String, onUndo: suspend () -> Unit) = show(message, "Undo", onUndo)

    companion object {
        val None = SnackbarController(null, null)
    }
}

val LocalSnackbarController = staticCompositionLocalOf { SnackbarController.None }

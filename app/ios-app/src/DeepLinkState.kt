package finance.shilling.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class DeepLinkAction {
    RECEIPT_CAMERA
}

object DeepLinkState {
    private val _pendingAction = MutableStateFlow<DeepLinkAction?>(null)
    val pendingAction: StateFlow<DeepLinkAction?> = _pendingAction

    fun setAction(action: DeepLinkAction?) {
        _pendingAction.value = action
    }

    fun consume() {
        _pendingAction.value = null
    }
}

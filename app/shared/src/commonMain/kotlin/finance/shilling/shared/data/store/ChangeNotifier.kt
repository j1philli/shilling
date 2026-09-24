package finance.shilling.shared.data.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class ChangeNotifier {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun notifyChanged() {
        _version.value = _version.value + 1
    }
}

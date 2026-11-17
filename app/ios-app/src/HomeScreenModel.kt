package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.HomeUiState
import finance.shilling.shared.presentation.HomeViewModel
import kotlinx.coroutines.flow.StateFlow

/** Swift-facing Home: `state` (current value) and `stateFlow` (AsyncSequence via KMP-NativeCoroutines). */
class HomeScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<HomeViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<HomeUiState> = viewModel.state
}

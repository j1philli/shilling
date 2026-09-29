package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.AccountEditorUiState
import finance.shilling.shared.presentation.AccountEditorViewModel
import finance.shilling.shared.presentation.CategoryEditorUiState
import finance.shilling.shared.presentation.CategoryEditorViewModel
import kotlinx.coroutines.flow.StateFlow

/** Swift-facing category editor; [categoryId] null creates one. */
class CategoryEditorScreenModel(categoryId: String?) : IosViewModelHost() {
    private val viewModel = viewModel<CategoryEditorViewModel>(categoryId)

    @NativeCoroutinesState
    val state: StateFlow<CategoryEditorUiState> = viewModel.state

    fun setName(value: String) = viewModel.setName(value)
    fun setColor(hex: String) = viewModel.setColor(hex)

    @NativeCoroutines
    suspend fun save(): String? = viewModel.save()

    @NativeCoroutines
    suspend fun delete(): String? = viewModel.delete()
}

/** Swift-facing account editor; [accountId] null creates one. */
class AccountEditorScreenModel(accountId: String?) : IosViewModelHost() {
    private val viewModel = viewModel<AccountEditorViewModel>(accountId)

    @NativeCoroutinesState
    val state: StateFlow<AccountEditorUiState> = viewModel.state

    fun setName(value: String) = viewModel.setName(value)
    fun setBalanceText(value: String) = viewModel.setBalanceText(value)

    @NativeCoroutines
    suspend fun save(): String? = viewModel.save()

    @NativeCoroutines
    suspend fun delete(): String? = viewModel.delete()
}

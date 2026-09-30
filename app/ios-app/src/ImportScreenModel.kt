package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.data.DateFormat
import finance.shilling.shared.presentation.ImportUiState
import finance.shilling.shared.presentation.ImportViewModel
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSData

/** Swift-facing CSV import. */
class ImportScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<ImportViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<ImportUiState> = viewModel.state

    fun loadFile(fileName: String, data: NSData) = viewModel.loadFile(fileName, data.toByteArray())
    fun setHasHeader(value: Boolean) = viewModel.setHasHeader(value)
    fun setDateColumn(index: Int) = viewModel.setDateColumn(index)
    fun setDescriptionColumn(index: Int) = viewModel.setDescriptionColumn(index)
    fun setAmountColumn(index: Int) = viewModel.setAmountColumn(index)
    fun setDateFormat(format: DateFormat) = viewModel.setDateFormat(format)
    fun setAccount(id: String?) = viewModel.setAccount(id)
    fun setDefaultCategory(id: String?) = viewModel.setDefaultCategory(id)
    fun setIncluded(rowIndex: Int, included: Boolean) = viewModel.setIncluded(rowIndex, included)
    fun setRowCategory(rowIndex: Int, categoryId: String?) = viewModel.setRowCategory(rowIndex, categoryId)

    @NativeCoroutines
    suspend fun importRows(): String? = viewModel.import()
}

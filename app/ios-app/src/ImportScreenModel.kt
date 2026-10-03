package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.data.DateFormat
import finance.shilling.shared.presentation.ImportUiState
import finance.shilling.shared.presentation.ImportViewModel
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/** Swift-facing CSV import. */
class ImportScreenModel : IosViewModelHost() {
    private val viewModel = viewModel<ImportViewModel>()

    @NativeCoroutinesState
    val state: StateFlow<ImportUiState> = viewModel.state

    @NativeCoroutines
    suspend fun loadFile(fileName: String, path: String) {
        val bytes = withContext(Dispatchers.IO) {
            (NSData.dataWithContentsOfFile(path) ?: error("Unable to read CSV")).toByteArray()
        }
        viewModel.loadFile(fileName, bytes)
    }
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

package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutines
import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.data.Frequency
import finance.shilling.shared.data.ScheduleType
import finance.shilling.shared.presentation.ScheduleEditorUiState
import finance.shilling.shared.presentation.ScheduleEditorViewModel
import kotlinx.coroutines.flow.StateFlow

/** Swift-facing schedule editor; [scheduleId] null creates one of [presetType]. */
class ScheduleEditorScreenModel(scheduleId: String?, presetType: ScheduleType?) : IosViewModelHost() {
    private val viewModel = viewModel<ScheduleEditorViewModel>(scheduleId, presetType)

    @NativeCoroutinesState
    val state: StateFlow<ScheduleEditorUiState> = viewModel.state

    fun setType(value: ScheduleType) = viewModel.setType(value)
    fun setTitle(value: String) = viewModel.setTitle(value)
    fun setAmountText(value: String) = viewModel.setAmountText(value)
    fun setAccount(id: String?) = viewModel.setAccount(id)
    fun setToAccount(id: String?) = viewModel.setToAccount(id)
    fun setCategory(id: String?) = viewModel.setCategory(id)
    fun setFrequency(value: Frequency) = viewModel.setFrequency(value)
    fun setInterval(value: Int) = viewModel.setIntervalText(value.toString())
    fun setStart(epochDay: Long) = viewModel.setStart(epochDay)
    fun setEnd(epochDay: Long) = viewModel.setEnd(epochDay)
    fun clearEnd() = viewModel.setEnd(null)
    fun setMonthDayText(value: String) = viewModel.setMonthDayText(value)
    fun setLastDay(value: Boolean) = viewModel.setLastDay(value)
    fun setNth(value: Int) = viewModel.setNth(value)
    fun setNthWeekday(index: Int) = viewModel.setNthWeekday(index)
    fun toggleWeekday(index: Int) = viewModel.toggleWeekday(index)
    fun setAutoPay(value: Boolean) = viewModel.setAutoPay(value)
    fun setNotes(value: String) = viewModel.setNotes(value)

    @NativeCoroutines
    suspend fun save(): String? = viewModel.save()

    @NativeCoroutines
    suspend fun delete(): String? = viewModel.delete()
}

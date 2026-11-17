package finance.shilling.app

import com.rickclephas.kmp.nativecoroutines.NativeCoroutinesState
import finance.shilling.shared.presentation.DisplayPreferences
import finance.shilling.shared.presentation.DisplayPrefs
import kotlinx.coroutines.flow.StateFlow

/** Swift access to the shared display preferences (theme mode drives the native appearance). */
object DisplayPreferencesBridge {
    @NativeCoroutinesState
    val prefs: StateFlow<DisplayPrefs> = DisplayPreferences.state
}

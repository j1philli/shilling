package finance.shilling.shared.presentation

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_CURRENCY_SYMBOL
import finance.shilling.shared.data.SETTINGS_KEY_THEME_MODE
import finance.shilling.shared.data.SETTINGS_KEY_WEEK_START
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DayOfWeek

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

data class DisplayPrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val currencySymbol: String = "$",
    val weekStart: DayOfWeek = DayOfWeek.FRIDAY
)

/**
 * User-facing display preferences, persisted in [Settings]. [state] drives every UI: Compose
 * re-reads it at the theme root, SwiftUI observes it through the iOS bridge.
 */
object DisplayPreferences {
    val currencyOptions = listOf("$", "€", "£", "¥", "₹", "C$", "A$", "CHF ")

    private val _state = MutableStateFlow(DisplayPrefs())
    val state: StateFlow<DisplayPrefs> = _state

    val themeMode: ThemeMode get() = _state.value.themeMode
    val currencySymbol: String get() = _state.value.currencySymbol
    val weekStart: DayOfWeek get() = _state.value.weekStart

    private var settings: Settings? = null

    fun load(settings: Settings) {
        this.settings = settings
        _state.value = DisplayPrefs(
            themeMode = settings.getStringOrNull(SETTINGS_KEY_THEME_MODE)
                ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
                ?: ThemeMode.SYSTEM,
            currencySymbol = settings.getStringOrNull(SETTINGS_KEY_CURRENCY_SYMBOL) ?: "$",
            weekStart = settings.getStringOrNull(SETTINGS_KEY_WEEK_START)
                ?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
                ?: DayOfWeek.FRIDAY
        )
    }

    fun updateThemeMode(mode: ThemeMode) {
        _state.update { it.copy(themeMode = mode) }
        settings?.putString(SETTINGS_KEY_THEME_MODE, mode.name)
    }

    fun updateCurrencySymbol(symbol: String) {
        _state.update { it.copy(currencySymbol = symbol) }
        settings?.putString(SETTINGS_KEY_CURRENCY_SYMBOL, symbol)
    }

    fun updateWeekStart(day: DayOfWeek) {
        _state.update { it.copy(weekStart = day) }
        settings?.putString(SETTINGS_KEY_WEEK_START, day.name)
    }
}

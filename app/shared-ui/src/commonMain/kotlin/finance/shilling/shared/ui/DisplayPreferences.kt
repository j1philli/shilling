package finance.shilling.shared.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.russhwolf.settings.Settings
import finance.shilling.shared.data.SETTINGS_KEY_CURRENCY_SYMBOL
import finance.shilling.shared.data.SETTINGS_KEY_THEME_MODE
import finance.shilling.shared.data.SETTINGS_KEY_WEEK_START
import kotlinx.datetime.DayOfWeek

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark")
}

/**
 * User-facing display preferences. Backed by snapshot state so any composable that
 * formats currency or reads the theme recomposes when a preference changes.
 */
object DisplayPreferences {
    val currencyOptions = listOf("$", "€", "£", "¥", "₹", "C$", "A$", "CHF ")

    var themeMode by mutableStateOf(ThemeMode.SYSTEM)
        private set
    var currencySymbol by mutableStateOf("$")
        private set
    var weekStart by mutableStateOf(DayOfWeek.FRIDAY)
        private set

    private var settings: Settings? = null

    fun load(settings: Settings) {
        this.settings = settings
        themeMode = settings.getStringOrNull(SETTINGS_KEY_THEME_MODE)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM
        currencySymbol = settings.getStringOrNull(SETTINGS_KEY_CURRENCY_SYMBOL) ?: "$"
        weekStart = settings.getStringOrNull(SETTINGS_KEY_WEEK_START)
            ?.let { name -> DayOfWeek.entries.firstOrNull { it.name == name } }
            ?: DayOfWeek.FRIDAY
    }

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        settings?.putString(SETTINGS_KEY_THEME_MODE, mode.name)
    }

    fun updateCurrencySymbol(symbol: String) {
        currencySymbol = symbol
        settings?.putString(SETTINGS_KEY_CURRENCY_SYMBOL, symbol)
    }

    fun updateWeekStart(day: DayOfWeek) {
        weekStart = day
        settings?.putString(SETTINGS_KEY_WEEK_START, day.name)
    }
}

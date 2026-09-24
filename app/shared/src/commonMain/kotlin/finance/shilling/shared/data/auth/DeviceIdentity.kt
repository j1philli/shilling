package finance.shilling.shared.data.auth

import com.russhwolf.settings.Settings
import finance.shilling.shared.data.IdGenerator
import finance.shilling.shared.data.SETTINGS_KEY_DEVICE_ID
import finance.shilling.shared.data.SETTINGS_KEY_LOCAL_HOUSEHOLD_ID

class DeviceIdentity(settings: Settings, idGenerator: IdGenerator) {
    val deviceId: String = settings.getStringOrNull(SETTINGS_KEY_DEVICE_ID)
        ?: idGenerator.newId().also { settings.putString(SETTINGS_KEY_DEVICE_ID, it) }
}

fun resolveHouseholdId(
    authService: AuthService,
    settings: Settings,
    idGenerator: IdGenerator
): String {
    return resolveLocalHouseholdId(settings, idGenerator)
}

fun resolveLocalHouseholdId(
    settings: Settings,
    idGenerator: IdGenerator
): String {
    return settings.getStringOrNull(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID)
        ?: idGenerator.newId().also { settings.putString(SETTINGS_KEY_LOCAL_HOUSEHOLD_ID, it) }
}

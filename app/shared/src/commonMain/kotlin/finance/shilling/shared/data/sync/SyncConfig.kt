package finance.shilling.shared.data.sync

data class SyncConfig(
    val serverUrl: String,
    val householdId: String,
    val deviceId: String,
    val enabled: Boolean = true
)

package finance.shilling.shared.data.backup

/** Lets account reset cancel backup/key-sync work before deleting local secrets and entities. */
class BackupSession {
    private val current = kotlinx.coroutines.flow.MutableStateFlow<BackupController?>(null)
    val controllerState: kotlinx.coroutines.flow.StateFlow<BackupController?> = current
    var controller: BackupController?
        get() = current.value
        set(value) { current.value = value }
    suspend fun stop() {
        val current = controller
        controller = null
        current?.close()
    }
}

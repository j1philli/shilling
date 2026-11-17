package finance.shilling.shared.data.backup

/** Lets account reset cancel backup/key-sync work before deleting local secrets and entities. */
class BackupSession {
    var controller: BackupController? = null
    suspend fun stop() {
        val current = controller
        controller = null
        current?.close()
    }
}

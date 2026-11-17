@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package finance.shilling.app

import finance.shilling.shared.data.backup.BackupKeyVault
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*

/** Device-only Keychain items are never included in an iCloud/device backup. */
class IosBackupKeyVault : BackupKeyVault {
    private fun query(namespace: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to "finance.shilling.backup.v1",
        kSecAttrAccount to namespace
    )
    private inline fun <T> dictionary(values: Map<Any?, Any?>, block: (CFDictionaryRef) -> T): T {
        val reference = CFBridgingRetain(values)!!
        return try { block(reference.reinterpret()) } finally { CFRelease(reference) }
    }
    override suspend fun read(namespace: String): String? = memScoped {
        val output = alloc<CFTypeRefVar>()
        val status = dictionary(query(namespace) + mapOf(kSecReturnData to true, kSecMatchLimit to kSecMatchLimitOne)) {
            SecItemCopyMatching(it, output.ptr)
        }
        if (status == errSecItemNotFound) return@memScoped null
        check(status == errSecSuccess) { "Unable to read recovery key" }
        val data = CFBridgingRelease(output.value) as NSData
        NSString.create(data, NSUTF8StringEncoding)?.toString() ?: error("Invalid stored recovery key")
    }
    override suspend fun write(namespace: String, secret: String) {
        val data = (secret as NSString).dataUsingEncoding(NSUTF8StringEncoding)!!
        val attributes = mapOf<Any?, Any?>(kSecValueData to data, kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
        val updated = dictionary(query(namespace)) { q -> dictionary(attributes) { SecItemUpdate(q, it) } }
        if (updated == errSecItemNotFound) {
            check(dictionary(query(namespace) + attributes) { SecItemAdd(it, null) } == errSecSuccess) { "Unable to save recovery key" }
        } else check(updated == errSecSuccess) { "Unable to save recovery key" }
    }
    override suspend fun delete(namespace: String) {
        val status = dictionary(query(namespace)) { SecItemDelete(it) }
        check(status == errSecSuccess || status == errSecItemNotFound) { "Unable to delete recovery key" }
    }
}

package finance.shilling.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import finance.shilling.shared.data.backup.BackupKeyVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidBackupKeyVault(context: Context) : BackupKeyVault {
    private val preferences = context.getSharedPreferences("shilling_backup_keys", Context.MODE_PRIVATE)
    private val alias = "shilling.backup.wrapping.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    private fun name(namespace: String) = Base64.getUrlEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(namespace.toByteArray()))
    override suspend fun read(namespace: String): String? = withContext(Dispatchers.IO) {
        val value = preferences.getString(name(namespace), null) ?: return@withContext null
        val bytes = Base64.getDecoder().decode(value)
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            updateAAD(namespace.toByteArray())
            doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        }
    }
    override suspend fun write(namespace: String, secret: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key()); updateAAD(namespace.toByteArray())
        }
        val encoded = Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(secret.toByteArray()))
        check(preferences.edit().putString(name(namespace), encoded).commit()) { "Unable to save recovery key" }
    }
    override suspend fun delete(namespace: String) = withContext(Dispatchers.IO) {
        check(preferences.edit().remove(name(namespace)).commit()) { "Unable to delete recovery key" }
    }
}
